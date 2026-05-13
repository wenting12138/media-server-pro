package com.wenting.mediaserver.protocol.webrtc.api;

/**
 * WebRTC ICE candidate (JS: RTCIceCandidate).
 */
public class RTCIceCandidate {

    private final String candidate;
    private final String sdpMid;
    private final int sdpMLineIndex;

    public RTCIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
        this.candidate = candidate;
        this.sdpMid = sdpMid;
        this.sdpMLineIndex = sdpMLineIndex;
    }

    public String getCandidate() { return candidate; }
    public String getSdpMid() { return sdpMid; }
    public int getSdpMLineIndex() { return sdpMLineIndex; }

    /**
     * Parse an SDP candidate attribute line into an RTCIceCandidate.
     * Input format: "a=candidate:foundation componentId transport priority ip port typ type ..."
     */
    public static RTCIceCandidate fromSdpAttribute(String line) {
        String val = line;
        if (val.startsWith("a=candidate:") || val.startsWith("candidate:")) {
            val = val.substring(val.indexOf("candidate:") + 10);
        }
        // We store the full original line for transmission, sdpMid/MLineIndex are set later
        return new RTCIceCandidate(line, null, 0);
    }

    public String toSdpAttribute() {
        return candidate;
    }

    @Override
    public String toString() {
        return candidate;
    }
}
