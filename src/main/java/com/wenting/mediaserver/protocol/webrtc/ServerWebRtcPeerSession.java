package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.remux.rtp.RtpPacketChunk;
import com.wenting.mediaserver.core.remux.rtp.RtpPayloadTypeResolver;
import com.wenting.mediaserver.core.remux.rtp.RtpSendTrackState;
import com.wenting.mediaserver.core.remux.rtp.RtspFrameToRtpPacketizer;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpSender;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service-layer wrapper around the generic WebRTC peer connection API.
 *
 * Keeps HTTP signaling and published-stream subscription state outside the
 * browser-style RTCPeerConnection object.
 */
public final class ServerWebRtcPeerSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerWebRtcPeerSession.class);

    private final String sessionId;
    private final StreamKey streamKey;
    private final RTCPeerConnection peerConnection;
    private final SessionDatagramIo datagramIo;
    private final MediaSubscriberAdapter subscriberAdapter;
    private final RtspFrameToRtpPacketizer frameToRtpPacketizer = new RtspFrameToRtpPacketizer();
    private final Map<String, InboundMediaFrame> latestConfigFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, RtpSendTrackState> rtpSendStatesByTrackId = new ConcurrentHashMap<String, RtpSendTrackState>();
    private final Map<String, Boolean> startedTracksByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstSrtpPacketLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstDropReasonLogged = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> firstNonConfigKeyFrameLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Boolean> configProfileLoggedByTrackId = new ConcurrentHashMap<String, Boolean>();
    private final AtomicLong outboundSrtpPacketCount = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile IPublishedStream publishedStream;
    private volatile InetSocketAddress remoteAddress;

    public ServerWebRtcPeerSession(
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

    public void attachPublishedStream(IPublishedStream stream) {
        if (stream == null) {
            return;
        }
        this.publishedStream = stream;
        stream.addSubscriber(subscriberAdapter);
    }

    public boolean acceptsTrack(String trackId) {
        return true;
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || packet.rtcp()) {
            return;
        }
        writeInboundFrame(packet.frame());
    }

    public synchronized void writeInboundFrame(InboundMediaFrame frame) {
        if (closed.get() || frame == null || frame.trackType() != TrackType.VIDEO || frame.codecType() != CodecType.H264) {
            return;
        }
        String trackId = normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(trackId, frame);
            logConfigProfileOnce(trackId, frame);
            if (isStarted(trackId)) {
                sendFrame(frame, frame);
            }
            return;
        }
        if (!canSendFrame(frame)) {
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

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            this.remoteAddress = remoteAddress;
        }
        datagramIo.receive(data, remoteAddress);
    }

    private boolean canSendFrame(InboundMediaFrame frame) {
        if (remoteAddress == null) {
            logDropOnce(normalizeTrackId(frame.trackId()), "no-remote",
                    "WebRTC cannot send yet: remote address not bound session={} stream={} track={}",
                    sessionId, streamKey, normalizeTrackId(frame.trackId()));
            return false;
        }
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        if (transceiver == null) {
            logDropOnce(normalizeTrackId(frame.trackId()), "no-transceiver",
                    "WebRTC cannot send yet: no send transceiver with SRTP context session={} stream={} track={}",
                    sessionId, streamKey, normalizeTrackId(frame.trackId()));
            return false;
        }
        return true;
    }

    private void sendFrame(InboundMediaFrame frame, InboundMediaFrame timestampFrame) {
        RTCRtpTransceiver transceiver = findSendTransceiver(frame);
        InetSocketAddress target = remoteAddress;
        if (transceiver == null || target == null) {
            return;
        }
        RTCRtpSender sender = transceiver.getSender();
        List<RtpPacketChunk> chunks = frameToRtpPacketizer.packetize(
                frame,
                null,
                latestConfigFramesByTrackId.get(normalizeTrackId(frame.trackId()))
        );
        if (chunks.isEmpty()) {
            logDropOnce(normalizeTrackId(frame.trackId()), "empty-chunks",
                    "WebRTC packetizer produced no RTP chunks session={} stream={} track={} config={} key={} bytes={}",
                    sessionId, streamKey, normalizeTrackId(frame.trackId()), frame.configFrame(), frame.keyFrame(), frame.payloadLength());
            return;
        }
        RtpSendTrackState sendState = sendState(frame.trackId(), sender.getSsrc());
        int payloadType = resolvePayloadType(transceiver, frame.codecType());
        int clockRate = resolveClockRate(transceiver, frame);
        long rtpTimestamp = sendState.toRtpTimestamp(clockRate, mediaTimestampMillis(timestampFrame));
        SrtpTransform transform = new SrtpTransform(sender.getSrtpContext(), sender.getSsrc());
        for (RtpPacketChunk chunk : chunks) {
            RtpPacket rtpPacket = new RtpPacket(
                    2,
                    false,
                    false,
                    0,
                    chunk.marker(),
                    payloadType,
                    sendState.nextSequenceNumber(),
                    rtpTimestamp,
                    sender.getSsrc(),
                    null,
                    chunk.payload()
            );
            byte[] srtpPacket = transform.protect(rtpPacket);
            logFirstSrtpPacket(frame, payloadType, clockRate, target, srtpPacket.length, chunk.marker());
            logPacketCount();
            datagramIo.send(srtpPacket, target).exceptionally(ex -> {
                log.warn("Failed to send WebRTC SRTP packet session={} stream={} target={}",
                        sessionId, streamKey, target, ex);
                return null;
            });
        }
    }

    private void logFirstSrtpPacket(InboundMediaFrame frame, int payloadType, int clockRate, InetSocketAddress target, int bytes, boolean marker) {
        String trackId = normalizeTrackId(frame.trackId());
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
            if (!matchesKind(transceiver, frame) || !canSend(transceiver)) {
                continue;
            }
            if (transceiver.getSender().getSrtpContext() == null) {
                continue;
            }
            return transceiver;
        }
        return null;
    }

    private boolean matchesKind(RTCRtpTransceiver transceiver, InboundMediaFrame frame) {
        return frame.trackType() == TrackType.VIDEO
                && transceiver.getKind() == com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack.Kind.VIDEO;
    }

    private boolean canSend(RTCRtpTransceiver transceiver) {
        return transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDONLY
                || transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDRECV;
    }

    private RtpSendTrackState sendState(String trackId, long ssrc) {
        String normalized = normalizeTrackId(trackId);
        RtpSendTrackState state = rtpSendStatesByTrackId.get(normalized);
        if (state != null) {
            return state;
        }
        RtpSendTrackState created = new RtpSendTrackState(ssrc);
        RtpSendTrackState existing = rtpSendStatesByTrackId.putIfAbsent(normalized, created);
        return existing == null ? created : existing;
    }

    private boolean isStarted(String trackId) {
        return Boolean.TRUE.equals(startedTracksByTrackId.get(normalizeTrackId(trackId)));
    }

    private int defaultClockRate(InboundMediaFrame frame) {
        return frame.trackType() == TrackType.VIDEO ? 90000 : 48000;
    }

    private int resolvePayloadType(RTCRtpTransceiver transceiver, CodecType codecType) {
        if (transceiver != null && transceiver.getNegotiatedPayloadType() != null) {
            return transceiver.getNegotiatedPayloadType().intValue();
        }
        return RtpPayloadTypeResolver.resolve(codecType);
    }

    private int resolveClockRate(RTCRtpTransceiver transceiver, InboundMediaFrame frame) {
        if (transceiver != null && transceiver.getNegotiatedClockRate() != null && transceiver.getNegotiatedClockRate().intValue() > 0) {
            return transceiver.getNegotiatedClockRate().intValue();
        }
        return defaultClockRate(frame);
    }

    private Long mediaTimestampMillis(InboundMediaFrame frame) {
        if (frame == null) {
            return null;
        }
        return frame.ptsMillis() == null ? frame.dtsMillis() : frame.ptsMillis();
    }

    private static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IPublishedStream stream = publishedStream;
        if (stream != null) {
            stream.removeSubscriber(sessionId);
        }
        peerConnection.close();
        datagramIo.close();
    }
}
