package com.wenting.mediaserver.protocol.webrtc.api;

/**
 * WebRTC session description (JS: RTCSessionDescription).
 */
public class RTCSessionDescription {

    private final String type;  // "offer" | "answer"
    private final String sdp;

    public RTCSessionDescription(String type, String sdp) {
        this.type = type;
        this.sdp = sdp;
    }

    public String getType() { return type; }
    public String getSdp() { return sdp; }

    @Override
    public String toString() {
        return type + "\r\n" + sdp;
    }
}
