package com.wenting.mediaserver.protocol.webrtc.ingest;

import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;

public final class TrackFeedbackState {
    public final RTCRtpTransceiver transceiver;
    public final String trackId;
    public final NackGenerator nackGenerator;
    public volatile InboundRtpReceiveStats stats;
    public volatile boolean firstReportLogged;
    public volatile long reportsSent;
    public volatile long nacksSent;
    public volatile long pliSent;
    public volatile long lastReceiverReportAtMs;
    public volatile long lastPliSentAtMs;
    public TrackFeedbackState(RTCRtpTransceiver transceiver, String trackId, NackGenerator nackGenerator) {
        this.transceiver = transceiver;
        this.trackId = trackId;
        this.nackGenerator = nackGenerator;
    }
}
