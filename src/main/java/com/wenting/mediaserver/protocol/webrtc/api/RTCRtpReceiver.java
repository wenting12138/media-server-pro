package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;

import java.util.function.Consumer;

/**
 * RTP receiver (analogous to JS RTCRtpReceiver).
 *
 * Holds the receive-side track, peer SSRC, and SRTP crypto context.
 * Decrypted RTP packets are delivered via the onPacket callback.
 */
public class RTCRtpReceiver {

    private final MediaStreamTrack track;
    private volatile long peerSsrc;
    private volatile SrtpCryptoContext srtpContext;
    private volatile Consumer<RtpPacket> onPacket;

    public RTCRtpReceiver(MediaStreamTrack track) {
        this.track = track;
    }

    public MediaStreamTrack getTrack() { return track; }
    public long getPeerSsrc() { return peerSsrc; }
    public void setPeerSsrc(long ssrc) { this.peerSsrc = ssrc & 0xFFFFFFFFL; }

    public SrtpCryptoContext getSrtpContext() { return srtpContext; }
    public void setSrtpContext(SrtpCryptoContext ctx) { this.srtpContext = ctx; }

    public Consumer<RtpPacket> getOnPacket() { return onPacket; }
    public void setOnPacket(Consumer<RtpPacket> handler) { this.onPacket = handler; }

    @Override
    public String toString() {
        return "RTCRtpReceiver{ssrc=" + peerSsrc + " track=" + track + "}";
    }
}
