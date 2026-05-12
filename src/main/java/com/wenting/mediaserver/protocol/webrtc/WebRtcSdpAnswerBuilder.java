package com.wenting.mediaserver.protocol.webrtc;

public final class WebRtcSdpAnswerBuilder {

    public String build(WebRtcPeerSession session, WebRtcOfferSummary offerSummary, String candidateIp, int candidatePort) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (offerSummary == null || !offerSummary.supportsH264Video()) {
            throw new IllegalArgumentException("offerSummary must advertise H264 video");
        }
        String host = candidateIp == null || candidateIp.trim().isEmpty() ? "127.0.0.1" : candidateIp.trim();
        int port = candidatePort > 0 ? candidatePort : 9;
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 0 IN IP4 ").append(host).append("\r\n");
        sdp.append("s=media-server-webrtc\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=group:BUNDLE ").append(offerSummary.videoMid()).append("\r\n");
        sdp.append("a=msid-semantic: WMS *\r\n");
        sdp.append("m=video ").append(port).append(" UDP/TLS/RTP/SAVPF ").append(offerSummary.h264PayloadType().intValue()).append("\r\n");
        sdp.append("c=IN IP4 ").append(host).append("\r\n");
        sdp.append("a=mid:").append(offerSummary.videoMid()).append("\r\n");
        sdp.append("a=sendonly\r\n");
        sdp.append("a=rtcp-mux\r\n");
        sdp.append("a=rtcp-rsize\r\n");
        sdp.append("a=ice-ufrag:").append(session.iceUfrag()).append("\r\n");
        sdp.append("a=ice-pwd:").append(session.icePwd()).append("\r\n");
        sdp.append("a=fingerprint:sha-256 ").append(session.fingerprint()).append("\r\n");
        sdp.append("a=setup:passive\r\n");
        sdp.append("a=ice-lite\r\n");
        sdp.append("a=rtpmap:").append(offerSummary.h264PayloadType().intValue()).append(" H264/90000\r\n");
        sdp.append("a=fmtp:").append(offerSummary.h264PayloadType().intValue()).append(" packetization-mode=1;profile-level-id=42e01f;level-asymmetry-allowed=1\r\n");
        sdp.append("a=ssrc:1001 cname:media-server\r\n");
        sdp.append("a=candidate:1 1 udp 2130706431 ").append(host).append(' ').append(port).append(" typ host\r\n");
        sdp.append("a=end-of-candidates\r\n");
        return sdp.toString();
    }
}
