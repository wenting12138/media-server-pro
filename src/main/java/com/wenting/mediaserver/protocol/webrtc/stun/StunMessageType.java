package com.wenting.mediaserver.protocol.webrtc.stun;

public enum StunMessageType {
    BINDING_REQUEST(0x0001),
    BINDING_SUCCESS_RESPONSE(0x0101);

    private final int code;

    StunMessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static StunMessageType fromCode(int code) {
        for (StunMessageType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
