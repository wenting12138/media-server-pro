package com.wenting.mediaserver.protocol.webrtc.core.ice;

/**
 * ICE candidate type (RFC 5245 Section 1).
 */
public enum CandidateType {
    HOST(126),              // 本地接口 IP
    PEER_REFLEXIVE(110),    // 对端从 STUN Binding Request 学到的地址
    SERVER_REFLEXIVE(100),  // 从 STUN 服务器学到的公网地址
    RELAYED(0);             // 通过 TURN 中继

    public final int preference;

    CandidateType(int preference) {
        this.preference = preference;
    }
}
