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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    }

    public synchronized void writeInboundFrame(InboundMediaFrame frame) {
        if (closed.get() || frame == null || frame.trackType() != TrackType.VIDEO || frame.codecType() != CodecType.H264) {
            return;
        }
        String trackId = normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(trackId, frame);
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
                return;
            }
            InboundMediaFrame configFrame = latestConfigFramesByTrackId.get(trackId);
            if (configFrame == null) {
                return;
            }
            sendFrame(configFrame, frame);
            startedTracksByTrackId.put(trackId, Boolean.TRUE);
        }
        sendFrame(frame, frame);
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            this.remoteAddress = remoteAddress;
        }
        datagramIo.receive(data, remoteAddress);
    }

    private boolean canSendFrame(InboundMediaFrame frame) {
        return remoteAddress != null && findSendTransceiver(frame) != null;
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
            return;
        }
        RtpSendTrackState sendState = sendState(frame.trackId(), sender.getSsrc());
        int payloadType = RtpPayloadTypeResolver.resolve(frame.codecType());
        int clockRate = defaultClockRate(frame);
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
            datagramIo.send(srtpPacket, target).exceptionally(ex -> {
                log.warn("Failed to send WebRTC SRTP packet session={} stream={} target={}",
                        sessionId, streamKey, target, ex);
                return null;
            });
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

    private Long mediaTimestampMillis(InboundMediaFrame frame) {
        if (frame == null) {
            return null;
        }
        return frame.dtsMillis() == null ? frame.ptsMillis() : frame.dtsMillis();
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
