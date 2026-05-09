package com.wenting.mediaserver.protocol.webrtc.stun;

public enum StunAttributeType {
    USERNAME(0x0006),
    MESSAGE_INTEGRITY(0x0008),
    PRIORITY(0x0024),
    USE_CANDIDATE(0x0025),
    XOR_MAPPED_ADDRESS(0x0020),
    ICE_CONTROLLED(0x8029),
    ICE_CONTROLLING(0x802A),
    FINGERPRINT(0x8028);

    private final int code;

    StunAttributeType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static StunAttributeType fromCode(int code) {
        for (StunAttributeType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
