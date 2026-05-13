package com.wenting.mediaserver.protocol.webrtc.core.sdp;

/**
 * WebRTC signaling message types.
 *
 * 对标 JS 的 RTCSessionDescription.type 和 ICE candidate 信号。
 * 消息体用 JSON 格式通过信令通道传输。
 */
public enum SignalingMessage {

    /** 发起端发送 SDP offer */
    OFFER,
    /** 接收端回复 SDP answer */
    ANSWER,
    /** 新 ICE candidate */
    ICE_CANDIDATE,
    /** 加入房间 */
    JOIN,
    /** 离开房间 */
    LEAVE,
    /** 成功加入 */
    JOINED,
    /** 用户已离开 */
    USER_LEFT;

    /**
     * 生成信令消息的 JSON 字符串。
     */
    public String toJson(String sdpOrCandidate, String from, String to) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(name().toLowerCase())
          .append("\",\"from\":\"").append(from != null ? from : "")
          .append("\",\"to\":\"").append(to != null ? to : "")
          .append("\",\"data\":\"")
          .append(escapeJson(sdpOrCandidate != null ? sdpOrCandidate : ""))
          .append("\"}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
