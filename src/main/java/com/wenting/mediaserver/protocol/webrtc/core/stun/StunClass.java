package com.wenting.mediaserver.protocol.webrtc.core.stun;

/**
 * STUN message class (RFC 5389).
 */
public final class StunClass {
    public static final int REQUEST         = 0b00;
    public static final int INDICATION      = 0b01;
    public static final int SUCCESS_RESPONSE = 0b10;
    public static final int ERROR_RESPONSE  = 0b11;

    private StunClass() {}
}
