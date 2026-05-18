package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;

/**
 * RTP sender (analogous to JS RTCRtpSender).
 *
 * Holds the track being sent and the send-side SRTP crypto context.
 * Actual RTP packetization and SRTP protection is done by the caller
 * using the crypto context obtained via getSrtpContext().
 */
public class RTCRtpSender {

    private volatile MediaStreamTrack track;
    private final long ssrc;
    private volatile SrtpCryptoContext srtpContext;
    private volatile RtcpFeedbackListener feedbackListener;

    public RTCRtpSender(MediaStreamTrack track, long ssrc) {
        this.track = track;
        this.ssrc = ssrc & 0xFFFFFFFFL;
    }

    public MediaStreamTrack getTrack() { return track; }
    public long getSsrc() { return ssrc; }

    public SrtpCryptoContext getSrtpContext() { return srtpContext; }
    public void setSrtpContext(SrtpCryptoContext ctx) { this.srtpContext = ctx; }
    public RtcpFeedbackListener getFeedbackListener() { return feedbackListener; }
    public void setFeedbackListener(RtcpFeedbackListener feedbackListener) { this.feedbackListener = feedbackListener; }

    /**
     * Replace the track being sent.
     */
    public void replaceTrack(MediaStreamTrack track) {
        this.track = track;
    }

    @Override
    public String toString() {
        return "RTCRtpSender{ssrc=" + ssrc + " track=" + track + "}";
    }
}
