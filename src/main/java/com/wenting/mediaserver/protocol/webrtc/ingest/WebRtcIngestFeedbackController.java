package com.wenting.mediaserver.protocol.webrtc.ingest;

import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpReceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives receive-side RTCP feedback for WebRTC publish sessions.
 */
public final class WebRtcIngestFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(WebRtcIngestFeedbackController.class);

    private final RTCPeerConnection peerConnection;
    private final Map<String, TrackFeedbackState> statesByTrackId = new ConcurrentHashMap<String, TrackFeedbackState>();

    public WebRtcIngestFeedbackController(RTCPeerConnection peerConnection) {
        this.peerConnection = peerConnection;
    }

    public void registerTransceiver(RTCRtpTransceiver transceiver) {
        if (transceiver == null
                || transceiver.getReceiver() == null
                || transceiver.getKind() != MediaStreamTrack.Kind.VIDEO) {
            return;
        }
        String trackId = trackId(transceiver);
        statesByTrackId.put(trackId, new TrackFeedbackState(transceiver, trackId));
    }

    public void onRtpPacket(RTCRtpTransceiver transceiver, RtpPacket packet) {
        if (transceiver == null || packet == null || transceiver.getReceiver() == null) {
            return;
        }
        TrackFeedbackState state = statesByTrackId.get(trackId(transceiver));
        if (state == null) {
            return;
        }
        if (state.stats == null || state.stats.mediaSsrc() != (packet.getSsrc() & 0xFFFFFFFFL)) {
            state.stats = new InboundRtpReceiveStats(packet.getSsrc());
        }
        int clockRate = transceiver.getNegotiatedClockRate() == null
                ? 90000
                : transceiver.getNegotiatedClockRate().intValue();
        state.stats.onRtpPacket(packet, clockRate, System.currentTimeMillis());
    }

    public void onRtcpPacket(RtcpPacket packet) {
        if (!(packet instanceof RtcpSenderReportPacket)) {
            return;
        }
        RtcpSenderReportPacket senderReport = (RtcpSenderReportPacket) packet;
        long nowMs = System.currentTimeMillis();
        for (TrackFeedbackState state : statesByTrackId.values()) {
            if (state == null || state.stats == null) {
                continue;
            }
            state.stats.onSenderReport(senderReport, nowMs);
        }
    }

    public void sendReceiverReports() {
        long nowMs = System.currentTimeMillis();
        for (TrackFeedbackState state : statesByTrackId.values()) {
            if (state == null || state.transceiver == null || state.stats == null || !state.stats.hasReceivedPackets()) {
                continue;
            }
            RTCRtpReceiver receiver = state.transceiver.getReceiver();
            if (receiver == null || receiver.getPeerSsrc() <= 0L) {
                continue;
            }
            boolean sent = peerConnection.sendReceiverReport(
                    state.transceiver.getSender().getSsrc(),
                    state.stats.snapshotReportBlock(nowMs)
            );
            if (sent) {
                state.reportsSent++;
                if (!state.firstReportLogged) {
                    state.firstReportLogged = true;
                    log.info("Sent first WebRTC ingest RR track={} mediaSsrc={} receiverSsrc={}",
                            state.trackId,
                            state.stats.mediaSsrc(),
                            state.transceiver.getSender().getSsrc() & 0xFFFFFFFFL);
                }
            }
        }
    }

    private String trackId(RTCRtpTransceiver transceiver) {
        String mid = transceiver.getMid();
        return mid == null || mid.trim().isEmpty() ? "track" : mid.trim();
    }

    private static final class TrackFeedbackState {
        private final RTCRtpTransceiver transceiver;
        private final String trackId;
        private volatile InboundRtpReceiveStats stats;
        private volatile boolean firstReportLogged;
        private volatile long reportsSent;

        private TrackFeedbackState(RTCRtpTransceiver transceiver, String trackId) {
            this.transceiver = transceiver;
            this.trackId = trackId;
        }
    }
}
