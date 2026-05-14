package com.wenting.mediaserver.protocol.webrtc.api;

/**
 * RTP transceiver (analogous to JS RTCRtpTransceiver).
 *
 * Pairs an RTCRtpSender and RTCRtpReceiver for a single media track,
 * with a direction attribute controlling send/receive behavior.
 */
public class RTCRtpTransceiver {

    public enum Direction { SENDRECV, SENDONLY, RECVONLY, INACTIVE }

    private final String mid;
    private final MediaStreamTrack.Kind kind;
    private final RTCRtpSender sender;
    private final RTCRtpReceiver receiver;
    private Direction direction;
    private volatile Integer negotiatedPayloadType;
    private volatile Integer negotiatedClockRate;

    public RTCRtpTransceiver(String mid, MediaStreamTrack.Kind kind,
                              RTCRtpSender sender, RTCRtpReceiver receiver) {
        this.mid = mid;
        this.kind = kind;
        this.sender = sender;
        this.receiver = receiver;
        this.direction = Direction.SENDRECV;
    }

    public String getMid() { return mid; }
    public MediaStreamTrack.Kind getKind() { return kind; }
    public RTCRtpSender getSender() { return sender; }
    public RTCRtpReceiver getReceiver() { return receiver; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
    public Integer getNegotiatedPayloadType() { return negotiatedPayloadType; }
    public void setNegotiatedPayloadType(Integer negotiatedPayloadType) { this.negotiatedPayloadType = negotiatedPayloadType; }
    public Integer getNegotiatedClockRate() { return negotiatedClockRate; }
    public void setNegotiatedClockRate(Integer negotiatedClockRate) { this.negotiatedClockRate = negotiatedClockRate; }

    @Override
    public String toString() {
        return "RTCRtpTransceiver{mid=" + mid + " kind=" + kind + " " + direction + "}";
    }
}
