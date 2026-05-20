package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.publish.video.H264RtpKeyFrameDetector;
import com.wenting.mediaserver.core.remux.rtp.RtpPacketChunk;
import com.wenting.mediaserver.core.remux.rtp.RtpSendTrackState;
import com.wenting.mediaserver.core.remux.rtp.RtspFrameToRtpPacketizer;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RtcpFeedbackListener;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpSender;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.cache.SentSrtpPacketCache;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import com.wenting.mediaserver.protocol.webrtc.util.WebrtcPlayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Service-layer wrapper around the generic WebRTC peer connection API.
 *
 * Keeps HTTP signaling and published-stream subscription state outside the
 * browser-style RTCPeerConnection object.
 */
public final class WebRtcPlaybackPeerSession implements WebRtcManagedSession {

    private static final Logger log = LoggerFactory.getLogger(WebRtcPlaybackPeerSession.class);

    private final String sessionId;
    private final StreamKey streamKey;
    private final RTCPeerConnection peerConnection;
    private final SessionDatagramIo datagramIo;
    private final MediaSubscriberAdapter subscriberAdapter;
    private final RtspFrameToRtpPacketizer frameToRtpPacketizer = new RtspFrameToRtpPacketizer();
    private final RtpPacketParser rtpPacketParser = new RtpPacketParser();
    private final H264RtpKeyFrameDetector h264RtpKeyFrameDetector = new H264RtpKeyFrameDetector();
    private final Map<String, InboundMediaFrame> latestConfigFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, RtpSendTrackState> rtpSendStatesByTrackId = new ConcurrentHashMap<String, RtpSendTrackState>();
    private final Map<String, Boolean> startedTracksByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstSrtpPacketLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstDropReasonLogged = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstNonConfigKeyFrameLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> configProfileLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> feedbackListenerBoundByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, SentSrtpPacketCache> sentPacketCachesByTrackId = new ConcurrentHashMap<String, SentSrtpPacketCache>();
    private final Map<String, List<byte[]>> latestConfigPacketsByTrackId = new ConcurrentHashMap<String, List<byte[]>>();
    private final Map<String, List<byte[]>> latestKeyFramePacketsByTrackId = new ConcurrentHashMap<String, List<byte[]>>();
    private final Map<String, List<InboundRtpPacket>> pendingSourcePacketsByTrackId = new ConcurrentHashMap<String, List<InboundRtpPacket>>();
    private final Map<String, InboundMediaFrame> pendingStartupConfigFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, InboundMediaFrame> pendingStartupKeyFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final AtomicLong outboundSrtpPacketCount = new AtomicLong(0);
    private final AtomicLong pliCount = new AtomicLong(0);
    private final AtomicLong nackCount = new AtomicLong(0);
    private final AtomicLong nackResendHitCount = new AtomicLong(0);
    private final AtomicLong nackResendMissCount = new AtomicLong(0);
    private final AtomicBoolean lifecycleCleanupInstalled = new AtomicBoolean(false);
    private final AtomicBoolean playbackActivated = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile IPublishedStream publishedStream;
    private volatile InetSocketAddress remoteAddress;
    private volatile RTCPeerConnection.ListenerSubscription connectionStateSubscription;
    private volatile RTCPeerConnection.ListenerSubscription iceStateSubscription;

    private static final int MAX_NACK_RESEND_PACKETS = 64;
    private static final int SENT_PACKET_CACHE_CAPACITY = 4096;
    private static final long SENT_PACKET_CACHE_MAX_AGE_MS = 2000L;
    private static final int MAX_PENDING_SOURCE_PACKETS_PER_TRACK = 32;

    public WebRtcPlaybackPeerSession(
            String sessionId,
            StreamKey streamKey,
            RTCPeerConnection peerConnection,
            SessionDatagramIo datagramIo
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.peerConnection = Objects.requireNonNull(peerConnection, "peerConnection");
        this.datagramIo = Objects.requireNonNull(datagramIo, "datagramIo");
        this.subscriberAdapter = new WebRtcSubscriberAdapter(this);
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public RTCPeerConnection peerConnection() {
        return peerConnection;
    }

    public SessionDatagramIo datagramIo() {
        return datagramIo;
    }

    public MediaSubscriberAdapter subscriberAdapter() {
        return subscriberAdapter;
    }

    public synchronized void attachPublishedStream(IPublishedStream stream) {
        this.publishedStream = stream;
        if (stream == null || !playbackActivated.get()) {
            return;
        }
        stream.addSubscriber(subscriberAdapter);
        requestInitialVideoKeyFrame(stream);
    }

    public synchronized void activatePlayback() {
        if (closed.get() || !playbackActivated.compareAndSet(false, true)) {
            return;
        }
        IPublishedStream stream = publishedStream;
        if (stream == null) {
            return;
        }
        stream.addSubscriber(subscriberAdapter);
        requestInitialVideoKeyFrame(stream);
    }

    public void installLifecycleCleanup(Runnable cleanupAction) {
        if (cleanupAction == null || !lifecycleCleanupInstalled.compareAndSet(false, true)) {
            return;
        }
        connectionStateSubscription = peerConnection.addConnectionStateListener(state -> {
            if (state == RTCPeerConnection.ConnectionState.CONNECTED) {
                activatePlayback();
            }
            if (state == RTCPeerConnection.ConnectionState.FAILED
                    || state == RTCPeerConnection.ConnectionState.CLOSED) {
                runCleanupAction(cleanupAction);
            }
        });
        iceStateSubscription = peerConnection.addIceConnectionStateListener(state -> {
            if (state == RTCPeerConnection.IceConnectionState.FAILED
                    || state == RTCPeerConnection.IceConnectionState.CLOSED) {
                runCleanupAction(cleanupAction);
            }
        });
        if (peerConnection.getConnectionState() == RTCPeerConnection.ConnectionState.CONNECTED) {
            activatePlayback();
        }
    }

    public boolean acceptsTrack(String trackId) {
        return true;
    }

    public synchronized void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || packet.rtcp()) {
            return;
        }
        flushPendingStartupIfReady();
        if (canRelaySourceRtpPacket(packet)) {
            if (!isSendReady(packet.frame())) {
                queuePendingSourcePacket(packet);
                return;
            }
            if (!canRelaySourcePacketNow(packet)) {
                return;
            }
            relaySourceRtpPacket(packet);
            return;
        }
        writeInboundFrame(packet.frame());
    }

    public synchronized void writeInboundFrame(InboundMediaFrame frame) {
        if (closed.get() || frame == null) {
            return;
        }
        flushPendingStartupIfReady();
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H264) {
            writeVideoFrame(frame);
            return;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            writeAudioFrame(frame);
        }
    }

    private void writeVideoFrame(InboundMediaFrame frame) {
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(trackId, frame);
            logConfigProfileOnce(trackId, frame);
            if (!isSendReady(frame)) {
                pendingStartupConfigFramesByTrackId.put(trackId, frame);
                return;
            }
            if (isStarted(trackId)) {
                sendFrame(frame, frame);
            }
            return;
        }
        if (!canSendFrame(frame)) {
            if (frame.keyFrame()) {
                pendingStartupKeyFramesByTrackId.put(trackId, frame);
            }
            return;
        }
        if (!isStarted(trackId)) {
            if (!frame.keyFrame()) {
                logDropOnce(trackId, "wait-keyframe",
                        "WebRTC track waiting keyframe session={} stream={} track={}",
                        sessionId, streamKey, trackId);
                return;
            }
            InboundMediaFrame configFrame = latestConfigFramesByTrackId.get(trackId);
            if (configFrame == null) {
                logDropOnce(trackId, "wait-config",
                        "WebRTC track waiting H264 config frame session={} stream={} track={}",
                        sessionId, streamKey, trackId);
                return;
            }
            sendFrame(configFrame, frame);
            startedTracksByTrackId.put(trackId, Boolean.TRUE);
        }
        sendFrame(frame, frame);
        if (frame.keyFrame() && !frame.configFrame()) {
            logFirstNonConfigKeyFrame(trackId, frame);
        }
    }

    private void writeAudioFrame(InboundMediaFrame frame) {
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(trackId, frame);
            return;
        }
        if (!canSendFrame(frame)) {
            return;
        }
        CodecType negotiatedCodec = resolveNegotiatedAudioCodec(frame);
        if (negotiatedCodec == CodecType.G711A || negotiatedCodec == CodecType.G711U) {
            if (frame.codecType() == negotiatedCodec) {
                sendFrame(frame, frame);
                return;
            }
        }
    }

    private CodecType resolveNegotiatedAudioCodec(InboundMediaFrame frame) {
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        if (transceiver == null || transceiver.getNegotiatedCodecType() == null) {
            return CodecType.UNKNOWN;
        }
        return transceiver.getNegotiatedCodecType();
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            this.remoteAddress = remoteAddress;
        }
        datagramIo.receive(data, remoteAddress);
        flushPendingStartupIfReady();
    }

    private boolean canSendFrame(InboundMediaFrame frame) {
        if (remoteAddress == null) {
            logDropOnce(WebrtcPlayUtil.normalizeTrackId(frame.trackId()), "no-remote",
                    "WebRTC cannot send yet: remote address not bound session={} stream={} track={}",
                    sessionId, streamKey, WebrtcPlayUtil.normalizeTrackId(frame.trackId()));
            return false;
        }
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        if (transceiver == null) {
            logDropOnce(WebrtcPlayUtil.normalizeTrackId(frame.trackId()), "no-transceiver",
                    "WebRTC cannot send yet: no send transceiver with SRTP context session={} stream={} track={}",
                    sessionId, streamKey, WebrtcPlayUtil.normalizeTrackId(frame.trackId()));
            return false;
        }
        return true;
    }

    private boolean isSendReady(InboundMediaFrame frame) {
        return frame != null && remoteAddress != null && findSendTransceiver(frame) != null;
    }

    private void sendFrame(InboundMediaFrame frame, InboundMediaFrame timestampFrame) {
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        InetSocketAddress target = remoteAddress;
        if (transceiver == null || target == null) {
            return;
        }
        RTCRtpSender sender = transceiver.getSender();
        bindFeedbackListenerIfNeeded(frame, transceiver);
        List<RtpPacketChunk> chunks = frameToRtpPacketizer.packetize(
                frame,
                null,
                latestConfigFramesByTrackId.get(WebrtcPlayUtil.normalizeTrackId(frame.trackId()))
        );
        if (chunks.isEmpty()) {
            logDropOnce(WebrtcPlayUtil.normalizeTrackId(frame.trackId()), "empty-chunks",
                    "WebRTC packetizer produced no RTP chunks session={} stream={} track={} config={} key={} bytes={}",
                    sessionId, streamKey, WebrtcPlayUtil.normalizeTrackId(frame.trackId()), frame.configFrame(), frame.keyFrame(), frame.payloadLength());
            return;
        }
        RtpSendTrackState sendState = sendState(frame.trackId(), sender.getSsrc());
        int payloadType = WebrtcPlayUtil.resolvePayloadType(transceiver, frame.codecType());
        int clockRate = WebrtcPlayUtil.resolveClockRate(transceiver, frame);
        long rtpTimestamp = sendState.toRtpTimestamp(clockRate, WebrtcPlayUtil.mediaTimestampMillis(timestampFrame));
        SrtpTransform transform = new SrtpTransform(sender.getSrtpContext(), sender.getSsrc());
        List<byte[]> sentPackets = new ArrayList<byte[]>(chunks.size());
        for (RtpPacketChunk chunk : chunks) {
            int sequenceNumber = sendState.nextSequenceNumber();
            RtpPacket rtpPacket = new RtpPacket(
                    2,
                    false,
                    false,
                    0,
                    chunk.marker(),
                    payloadType,
                    sequenceNumber,
                    rtpTimestamp,
                    sender.getSsrc(),
                    null,
                    chunk.payload()
            );
            byte[] srtpPacket = transform.protect(rtpPacket);
            sentPackets.add(Arrays.copyOf(srtpPacket, srtpPacket.length));
            cacheSentVideoPacket(frame, sequenceNumber, srtpPacket);
            logFirstSrtpPacket(frame, payloadType, clockRate, target, srtpPacket.length, chunk.marker());
            datagramIo.send(srtpPacket, target).exceptionally(ex -> {
                log.warn("Failed to send WebRTC SRTP packet session={} stream={} target={}",
                        sessionId, streamKey, target, ex);
                return null;
            });
        }
        rememberReplayPackets(frame, sentPackets);
    }

    private boolean canRelaySourceRtpPacket(InboundRtpPacket packet) {
        InboundMediaFrame frame = packet == null ? null : packet.frame();
        return frame != null
                && frame.sourceProtocol() == StreamProtocol.WEBRTC
                && frame.trackType() == TrackType.VIDEO
                && frame.codecType() == CodecType.H264;
    }

    private void relaySourceRtpPacket(InboundRtpPacket packet) {
        InboundMediaFrame frame = packet.frame();
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        InetSocketAddress target = remoteAddress;
        if (transceiver == null || target == null) {
            return;
        }
        bindFeedbackListenerIfNeeded(frame, transceiver);
        RtpParseResult parseResult = rtpPacketParser.parse(frame.payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null || header.payloadLength() <= 0) {
            logDropOnce(WebrtcPlayUtil.normalizeTrackId(frame.trackId()), "invalid-source-rtp",
                    "WebRTC direct RTP relay ignored invalid packet session={} stream={} track={} bytes={}",
                    sessionId, streamKey, WebrtcPlayUtil.normalizeTrackId(frame.trackId()), frame.payloadLength());
            return;
        }
        RtpSendTrackState sendState = sendState(frame.trackId(), transceiver.getSender().getSsrc());
        int payloadType = WebrtcPlayUtil.resolvePayloadType(transceiver, frame.codecType());
        byte[] payload = Arrays.copyOfRange(frame.payload(), header.payloadOffset(), header.payloadOffset() + header.payloadLength());
        RtpPacket rtpPacket = new RtpPacket(
                2,
                false,
                false,
                0,
                header.marker(),
                payloadType,
                sendState.nextSequenceNumber(),
                header.timestamp(),
                transceiver.getSender().getSsrc(),
                null,
                payload
        );
        byte[] srtpPacket = new SrtpTransform(transceiver.getSender().getSrtpContext(), transceiver.getSender().getSsrc())
                .protect(rtpPacket);
        cacheSentVideoPacket(frame, rtpPacket.getSequenceNumber(), srtpPacket);
        logFirstSrtpPacket(frame, payloadType, packet.clockRate(), target, srtpPacket.length, header.marker());
        datagramIo.send(srtpPacket, target).exceptionally(ex -> {
            log.warn("Failed to relay WebRTC source RTP packet session={} stream={} target={}",
                    sessionId, streamKey, target, ex);
            return null;
        });
    }

    private void queuePendingSourcePacket(InboundRtpPacket packet) {
        if (packet == null || packet.frame() == null) {
            return;
        }
        String trackId = WebrtcPlayUtil.normalizeTrackId(packet.frame().trackId());
        List<InboundRtpPacket> packets = pendingSourcePacketsByTrackId.get(trackId);
        if (packets == null) {
            packets = new ArrayList<InboundRtpPacket>();
            List<InboundRtpPacket> raced = pendingSourcePacketsByTrackId.putIfAbsent(trackId, packets);
            if (raced != null) {
                packets = raced;
            }
        }
        if (packets.size() >= MAX_PENDING_SOURCE_PACKETS_PER_TRACK) {
            packets.remove(0);
        }
        packets.add(packet);
    }

    private synchronized void flushPendingStartupIfReady() {
        flushPendingStartupFramesIfReady();
        flushPendingSourcePacketsIfReady();
    }

    private void flushPendingStartupFramesIfReady() {
        if (pendingStartupConfigFramesByTrackId.isEmpty() && pendingStartupKeyFramesByTrackId.isEmpty()) {
            return;
        }
        List<String> trackIds = new ArrayList<String>(pendingStartupConfigFramesByTrackId.keySet());
        for (String trackId : pendingStartupKeyFramesByTrackId.keySet()) {
            if (!trackIds.contains(trackId)) {
                trackIds.add(trackId);
            }
        }
        for (String trackId : trackIds) {
            InboundMediaFrame configFrame = pendingStartupConfigFramesByTrackId.get(trackId);
            InboundMediaFrame keyFrame = pendingStartupKeyFramesByTrackId.get(trackId);
            InboundMediaFrame readinessFrame = keyFrame != null ? keyFrame : configFrame;
            if (!isSendReady(readinessFrame)) {
                continue;
            }
            pendingStartupConfigFramesByTrackId.remove(trackId);
            pendingStartupKeyFramesByTrackId.remove(trackId);
            if (configFrame != null) {
                latestConfigFramesByTrackId.put(trackId, configFrame);
            }
            if (keyFrame != null) {
                if (configFrame != null) {
                    sendFrame(configFrame, keyFrame);
                }
                startedTracksByTrackId.put(trackId, Boolean.TRUE);
                sendFrame(keyFrame, keyFrame);
                logFirstNonConfigKeyFrame(trackId, keyFrame);
            } else if (configFrame != null && isStarted(trackId)) {
                sendFrame(configFrame, configFrame);
            }
        }
    }

    private void flushPendingSourcePacketsIfReady() {
        if (pendingSourcePacketsByTrackId.isEmpty()) {
            return;
        }
        List<String> readyTrackIds = new ArrayList<String>(pendingSourcePacketsByTrackId.keySet());
        for (String trackId : readyTrackIds) {
            List<InboundRtpPacket> pendingPackets = pendingSourcePacketsByTrackId.get(trackId);
            if (pendingPackets == null || pendingPackets.isEmpty()) {
                pendingSourcePacketsByTrackId.remove(trackId);
                continue;
            }
            InboundMediaFrame firstFrame = pendingPackets.get(0) == null ? null : pendingPackets.get(0).frame();
            if (!isSendReady(firstFrame)) {
                continue;
            }
            pendingSourcePacketsByTrackId.remove(trackId);
            for (InboundRtpPacket pendingPacket : pendingPackets) {
                if (pendingPacket != null
                        && canRelaySourceRtpPacket(pendingPacket)
                        && canRelaySourcePacketNow(pendingPacket)) {
                    relaySourceRtpPacket(pendingPacket);
                }
            }
        }
    }

    private boolean canRelaySourcePacketNow(InboundRtpPacket packet) {
        InboundMediaFrame frame = packet == null ? null : packet.frame();
        if (frame == null) {
            return false;
        }
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (isStarted(trackId)) {
            return true;
        }
        RtpParseResult parseResult = rtpPacketParser.parse(frame.payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null) {
            logDropOnce(trackId, "invalid-source-startup",
                    "WebRTC direct RTP relay waiting valid startup packet session={} stream={} track={}",
                    sessionId, streamKey, trackId);
            return false;
        }
        if (!h264RtpKeyFrameDetector.isKeyFrame(frame.payload(), header)) {
            logDropOnce(trackId, "wait-source-keyframe",
                    "WebRTC direct RTP relay waiting startup keyframe session={} stream={} track={}",
                    sessionId, streamKey, trackId);
            return false;
        }
        startedTracksByTrackId.put(trackId, Boolean.TRUE);
        return true;
    }

    private void logFirstSrtpPacket(InboundMediaFrame frame, int payloadType, int clockRate, InetSocketAddress target, int bytes, boolean marker) {
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (Boolean.TRUE.equals(firstSrtpPacketLoggedByTrackId.putIfAbsent(trackId, Boolean.TRUE))) {
            return;
        }
        log.info("First WebRTC SRTP packet sent session={} stream={} track={} target={} payloadType={} clockRate={} bytes={} keyFrame={} configFrame={} marker={}",
                sessionId, streamKey, trackId, target, payloadType, clockRate, bytes, frame.keyFrame(), frame.configFrame(), marker);
    }

    private void logDropOnce(String trackId, String reason, String pattern, Object... args) {
        String key = trackId + "|" + reason;
        if (Boolean.TRUE.equals(firstDropReasonLogged.putIfAbsent(key, Boolean.TRUE))) {
            return;
        }
        log.info(pattern, args);
    }

    private void logFirstNonConfigKeyFrame(String trackId, InboundMediaFrame frame) {
        if (Boolean.TRUE.equals(firstNonConfigKeyFrameLoggedByTrackId.putIfAbsent(trackId, Boolean.TRUE))) {
            return;
        }
        log.info("First non-config keyframe sent session={} stream={} track={} bytes={} pts={} dts={}",
                sessionId, streamKey, trackId, frame.payloadLength(), frame.ptsMillis(), frame.dtsMillis());
    }

    private void logConfigProfileOnce(String trackId, InboundMediaFrame frame) {
        if (frame == null || frame.payload() == null || frame.payloadLength() < 4) {
            return;
        }
        if (Boolean.TRUE.equals(configProfileLoggedByTrackId.putIfAbsent(trackId, Boolean.TRUE))) {
            return;
        }
        byte[] p = frame.payload();
        int profile = p[1] & 0xFF;
        int compat = p[2] & 0xFF;
        int level = p[3] & 0xFF;
        String profileLevelId = toHex(profile) + toHex(compat) + toHex(level);
        log.info("WebRTC H264 config observed session={} stream={} track={} profile-level-id={} configBytes={}",
                sessionId, streamKey, trackId, profileLevelId, frame.payloadLength());
    }

    private static String toHex(int value) {
        return String.format(Locale.ROOT, "%02x", value & 0xFF);
    }

    private void logPacketCount() {
        long count = outboundSrtpPacketCount.incrementAndGet();
        if (count == 1 || count % 500 == 0) {
            log.info("WebRTC SRTP packet count session={} stream={} packets={}",
                    sessionId, streamKey, count);
        }
    }

    private RTCRtpTransceiver findSendTransceiver(InboundMediaFrame frame) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (transceiver == null || transceiver.getSender() == null) {
                continue;
            }
            if (!WebrtcPlayUtil.matchesKind(transceiver, frame) || !WebrtcPlayUtil.canSend(transceiver)) {
                continue;
            }
            if (transceiver.getNegotiatedPayloadType() == null) {
                continue;
            }
            if (transceiver.getSender().getSrtpContext() == null) {
                continue;
            }
            return transceiver;
        }
        return null;
    }

    private void bindFeedbackListenerIfNeeded(InboundMediaFrame frame, RTCRtpTransceiver transceiver) {
        if (frame == null || transceiver == null || transceiver.getSender() == null || frame.trackType() != TrackType.VIDEO) {
            return;
        }
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (Boolean.TRUE.equals(feedbackListenerBoundByTrackId.putIfAbsent(trackId, Boolean.TRUE))) {
            return;
        }
        transceiver.getSender().setFeedbackListener(new RtcpFeedbackListener() {
            @Override
            public void onPictureLossIndication(long mediaSsrc) {
                long count = pliCount.incrementAndGet();
                log.info("WebRTC PLI received session={} stream={} track={} mediaSsrc={} totalPli={}",
                        sessionId, streamKey, trackId, mediaSsrc, count);
                replayLatestRecoveryPackets(trackId);
                requestFreshKeyFrame(trackId);
            }

            @Override
            public void onGenericNack(long mediaSsrc, List<Integer> lostSequenceNumbers) {
                long count = nackCount.incrementAndGet();
                log.info("WebRTC NACK received session={} stream={} track={} mediaSsrc={} lostSeqCount={} lostSeqs={} totalNack={}",
                        sessionId, streamKey, trackId, mediaSsrc,
                        lostSequenceNumbers == null ? 0 : lostSequenceNumbers.size(),
                        lostSequenceNumbers, count);
                resendLostPackets(trackId, lostSequenceNumbers);
            }
        });
    }

    private void cacheSentVideoPacket(InboundMediaFrame frame, int sequenceNumber, byte[] srtpPacket) {
        if (frame == null || frame.trackType() != TrackType.VIDEO || srtpPacket == null) {
            return;
        }
        sentPacketCache(frame.trackId()).put(sequenceNumber, srtpPacket, System.currentTimeMillis());
    }

    private void rememberReplayPackets(InboundMediaFrame frame, List<byte[]> sentPackets) {
        if (frame == null || sentPackets == null || sentPackets.isEmpty() || frame.trackType() != TrackType.VIDEO) {
            return;
        }
        String trackId = WebrtcPlayUtil.normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigPacketsByTrackId.put(trackId, immutablePacketCopies(sentPackets));
            return;
        }
        if (frame.keyFrame()) {
            latestKeyFramePacketsByTrackId.put(trackId, immutablePacketCopies(sentPackets));
        }
    }

    private void resendLostPackets(String trackId, List<Integer> lostSequenceNumbers) {
        InetSocketAddress target = remoteAddress;
        if (target == null || lostSequenceNumbers == null || lostSequenceNumbers.isEmpty()) {
            return;
        }
        SentSrtpPacketCache cache = sentPacketCache(trackId);
        int attempts = Math.min(lostSequenceNumbers.size(), MAX_NACK_RESEND_PACKETS);
        int hits = 0;
        int misses = 0;
        for (int i = 0; i < attempts; i++) {
            Integer sequenceNumber = lostSequenceNumbers.get(i);
            if (sequenceNumber == null) {
                continue;
            }
            byte[] packet = cache.get(sequenceNumber.intValue(), System.currentTimeMillis());
            if (packet == null) {
                misses++;
                continue;
            }
            hits++;
            datagramIo.send(Arrays.copyOf(packet, packet.length), target).exceptionally(ex -> {
                log.warn("Failed to resend WebRTC SRTP packet session={} stream={} target={} seq={}",
                        sessionId, streamKey, target, sequenceNumber, ex);
                return null;
            });
        }
        nackResendHitCount.addAndGet(hits);
        nackResendMissCount.addAndGet(misses);
        log.info("WebRTC NACK resend summary session={} stream={} track={} requested={} attempted={} hits={} misses={} totalHit={} totalMiss={}",
                sessionId, streamKey, trackId, lostSequenceNumbers.size(), attempts, hits, misses,
                nackResendHitCount.get(), nackResendMissCount.get());
    }

    private void replayLatestRecoveryPackets(String trackId) {
        InetSocketAddress target = remoteAddress;
        if (target == null) {
            return;
        }
        List<byte[]> configPackets = latestConfigPacketsByTrackId.get(trackId);
        List<byte[]> keyFramePackets = latestKeyFramePacketsByTrackId.get(trackId);
        int replayed = replayPackets(configPackets, target);
        replayed += replayPackets(keyFramePackets, target);
        log.info("WebRTC PLI recovery replay session={} stream={} track={} replayedPackets={} hasConfig={} hasKeyframe={}",
                sessionId, streamKey, trackId, replayed,
                configPackets != null && !configPackets.isEmpty(),
                keyFramePackets != null && !keyFramePackets.isEmpty());
    }

    private void requestFreshKeyFrame(String trackId) {
        IPublishedStream stream = publishedStream;
        if (stream == null) {
            return;
        }
        boolean accepted = stream.requestKeyFrame(trackId);
        log.info("WebRTC keyframe request forwarded session={} stream={} track={} accepted={}",
                sessionId, streamKey, trackId, accepted);
    }

    private int replayPackets(List<byte[]> packets, InetSocketAddress target) {
        if (packets == null || packets.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (byte[] packet : packets) {
            if (packet == null || packet.length == 0) {
                continue;
            }
            count++;
            datagramIo.send(Arrays.copyOf(packet, packet.length), target).exceptionally(ex -> {
                log.warn("Failed to replay WebRTC SRTP recovery packet session={} stream={} target={}",
                        sessionId, streamKey, target, ex);
                return null;
            });
        }
        return count;
    }

    private SentSrtpPacketCache sentPacketCache(String trackId) {
        String normalized = WebrtcPlayUtil.normalizeTrackId(trackId);
        SentSrtpPacketCache existing = sentPacketCachesByTrackId.get(normalized);
        if (existing != null) {
            return existing;
        }
        SentSrtpPacketCache created = new SentSrtpPacketCache(SENT_PACKET_CACHE_CAPACITY, SENT_PACKET_CACHE_MAX_AGE_MS);
        SentSrtpPacketCache raced = sentPacketCachesByTrackId.putIfAbsent(normalized, created);
        return raced == null ? created : raced;
    }

    private List<byte[]> immutablePacketCopies(List<byte[]> packets) {
        if (packets == null || packets.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> copies = new ArrayList<byte[]>(packets.size());
        for (byte[] packet : packets) {
            copies.add(packet == null ? new byte[0] : Arrays.copyOf(packet, packet.length));
        }
        return Collections.unmodifiableList(copies);
    }

    private RtpSendTrackState sendState(String trackId, long ssrc) {
        String normalized = WebrtcPlayUtil.normalizeTrackId(trackId);
        RtpSendTrackState state = rtpSendStatesByTrackId.get(normalized);
        if (state != null) {
            return state;
        }
        RtpSendTrackState created = new RtpSendTrackState(ssrc);
        RtpSendTrackState existing = rtpSendStatesByTrackId.putIfAbsent(normalized, created);
        return existing == null ? created : existing;
    }

    private boolean isStarted(String trackId) {
        return Boolean.TRUE.equals(startedTracksByTrackId.get(WebrtcPlayUtil.normalizeTrackId(trackId)));
    }

    private void requestInitialVideoKeyFrame(IPublishedStream stream) {
        if (stream == null) {
            return;
        }
        String trackId = stream.firstVideoTrackId();
        if (trackId == null || trackId.trim().isEmpty()) {
            return;
        }
        boolean accepted = stream.requestKeyFrame(trackId);
        log.debug("Requested initial WebRTC playback keyframe session={} stream={} track={} accepted={}",
                sessionId, streamKey, trackId, accepted);
    }

    private void runCleanupAction(Runnable cleanupAction) {
        try {
            cleanupAction.run();
        } catch (RuntimeException e) {
            log.warn("WebRTC playback cleanup callback failed session={} stream={}: {}",
                    sessionId, streamKey, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeSubscription(connectionStateSubscription);
        closeSubscription(iceStateSubscription);
        IPublishedStream stream = publishedStream;
        if (stream != null) {
            stream.removeSubscriber(sessionId);
        }
        peerConnection.close();
        datagramIo.close();
    }

    private void closeSubscription(RTCPeerConnection.ListenerSubscription subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (RuntimeException e) {
            log.debug("Ignoring playback listener close failure session={} stream={}: {}",
                    sessionId, streamKey, e.getMessage(), e);
        }
    }
}
