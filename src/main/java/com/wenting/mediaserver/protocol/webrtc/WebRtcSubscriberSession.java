package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.remux.rtp.RtpPacketChunk;
import com.wenting.mediaserver.core.remux.rtp.RtpSendTrackState;
import com.wenting.mediaserver.core.remux.rtp.RtspFrameToRtpPacketizer;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsServerTransport;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsTransportState;
import com.wenting.mediaserver.protocol.webrtc.srtp.SrtpPacketEncoder;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebRtcSubscriberSession {

    private static final int H264_CLOCK_RATE = 90000;
    private static final int H264_PAYLOAD_TYPE = 97;

    private final WebRtcPeerSession peerSession;
    private final WebRtcDatagramSender datagramSender;
    private final RtspFrameToRtpPacketizer frameToRtpPacketizer = new RtspFrameToRtpPacketizer();
    private final RtpToRtmpFrameAssembler rtpToFrameAssembler = new RtpToRtmpFrameAssembler();
    private final SrtpPacketEncoder srtpPacketEncoder = new SrtpPacketEncoder();
    private final Map<String, InboundMediaFrame> latestConfigFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, InboundMediaFrame> latestKeyFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, InboundMediaFrame> lastFlushedConfigFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, InboundMediaFrame> lastFlushedKeyFramesByTrackId = new ConcurrentHashMap<String, InboundMediaFrame>();
    private final Map<String, RtpSendTrackState> sendStatesByTrackId = new ConcurrentHashMap<String, RtpSendTrackState>();
    private final Map<String, Boolean> startupFlushedByTrackId = new ConcurrentHashMap<String, Boolean>();

    public WebRtcSubscriberSession(WebRtcPeerSession peerSession, WebRtcDatagramSender datagramSender) {
        this.peerSession = peerSession;
        this.datagramSender = datagramSender;
    }

    public String sessionId() {
        return peerSession == null ? null : peerSession.sessionId();
    }

    public boolean acceptsTrack(String trackId) {
        return true;
    }

    public void writeInboundFrame(InboundMediaFrame frame) {
        if (!supportsFrame(frame)) {
            return;
        }
        rememberStartupFrame(frame);
        if (!mediaReady()) {
            return;
        }
        if (!startupFlushed(frame.trackId())) {
            flushStartupFrames(frame.trackId());
        }
        if (shouldSkipAfterStartupFlush(frame)) {
            return;
        }
        sendFrame(frame);
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || packet.rtcp()) {
            return;
        }
        List<InboundMediaFrame> frames = rtpToFrameAssembler.assemble(packet, null);
        for (InboundMediaFrame frame : frames) {
            writeInboundFrame(frame);
        }
    }

    private boolean supportsFrame(InboundMediaFrame frame) {
        return frame != null
                && frame.trackType() == TrackType.VIDEO
                && frame.codecType() == CodecType.H264;
    }

    private void rememberStartupFrame(InboundMediaFrame frame) {
        String trackId = normalizeTrackId(frame.trackId());
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(trackId, frame);
        }
        if (frame.keyFrame()) {
            latestKeyFramesByTrackId.put(trackId, frame);
        }
    }

    private boolean mediaReady() {
        if (peerSession == null || peerSession.remoteAddress() == null) {
            return false;
        }
        DtlsServerTransport dtlsServerTransport = peerSession.dtlsServerTransport();
        return dtlsServerTransport != null && dtlsServerTransport.state() == DtlsTransportState.SRTP_KEYING_EXPORTED;
    }

    private boolean startupFlushed(String trackId) {
        Boolean value = startupFlushedByTrackId.get(normalizeTrackId(trackId));
        return Boolean.TRUE.equals(value);
    }

    private void flushStartupFrames(String trackId) {
        String normalizedTrackId = normalizeTrackId(trackId);
        InboundMediaFrame configFrame = latestConfigFramesByTrackId.get(normalizedTrackId);
        if (configFrame != null) {
            sendFrame(configFrame);
            lastFlushedConfigFramesByTrackId.put(normalizedTrackId, configFrame);
        }
        InboundMediaFrame keyFrame = latestKeyFramesByTrackId.get(normalizedTrackId);
        if (keyFrame != null && keyFrame != configFrame && !keyFrame.configFrame()) {
            sendFrame(keyFrame);
            lastFlushedKeyFramesByTrackId.put(normalizedTrackId, keyFrame);
        }
        startupFlushedByTrackId.put(normalizedTrackId, Boolean.TRUE);
    }

    private boolean shouldSkipAfterStartupFlush(InboundMediaFrame frame) {
        if (frame == null || !startupFlushed(frame.trackId())) {
            return false;
        }
        String trackId = normalizeTrackId(frame.trackId());
        return frame == lastFlushedConfigFramesByTrackId.get(trackId)
                || frame == lastFlushedKeyFramesByTrackId.get(trackId);
    }

    private void sendFrame(InboundMediaFrame frame) {
        List<RtpPacketChunk> chunks = frameToRtpPacketizer.packetize(
                frame,
                null,
                latestConfigFramesByTrackId.get(normalizeTrackId(frame.trackId()))
        );
        if (chunks.isEmpty()) {
            return;
        }
        RtpSendTrackState sendTrackState = sendTrackState(frame.trackId());
        long rtpTimestamp = sendTrackState.toRtpTimestamp(H264_CLOCK_RATE, frame.dtsMillis() == null ? frame.ptsMillis() : frame.dtsMillis());
        InetSocketAddress remoteAddress = peerSession.remoteAddress();
        DtlsServerTransport dtlsServerTransport = peerSession.dtlsServerTransport();
        for (RtpPacketChunk chunk : chunks) {
            byte[] rtpPacket = buildRtpPacket(chunk, sendTrackState, rtpTimestamp);
            byte[] protectedPacket = srtpPacketEncoder.protectRtp(rtpPacket, dtlsServerTransport == null ? null : dtlsServerTransport.srtpKeyingMaterial());
            if (protectedPacket.length > 0) {
                datagramSender.send(protectedPacket, remoteAddress);
            }
        }
    }

    private byte[] buildRtpPacket(RtpPacketChunk chunk, RtpSendTrackState sendTrackState, long rtpTimestamp) {
        byte[] payload = chunk.payload();
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (H264_PAYLOAD_TYPE & 0x7F);
        if (chunk.marker()) {
            packet[1] |= (byte) 0x80;
        }
        int sequenceNumber = sendTrackState.nextSequenceNumber();
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((rtpTimestamp >> 24) & 0xFF);
        packet[5] = (byte) ((rtpTimestamp >> 16) & 0xFF);
        packet[6] = (byte) ((rtpTimestamp >> 8) & 0xFF);
        packet[7] = (byte) (rtpTimestamp & 0xFF);
        long ssrc = sendTrackState.ssrc();
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return packet;
    }

    private RtpSendTrackState sendTrackState(String trackId) {
        String normalizedTrackId = normalizeTrackId(trackId);
        RtpSendTrackState existing = sendStatesByTrackId.get(normalizedTrackId);
        if (existing != null) {
            return existing;
        }
        RtpSendTrackState created = new RtpSendTrackState();
        RtpSendTrackState previous = sendStatesByTrackId.putIfAbsent(normalizedTrackId, created);
        return previous == null ? created : previous;
    }

    private String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }
}
