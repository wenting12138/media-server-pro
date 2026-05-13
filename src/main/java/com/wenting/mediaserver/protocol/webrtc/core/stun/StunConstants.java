package com.wenting.mediaserver.protocol.webrtc.core.stun;

/**
 * STUN protocol constants (RFC 5389).
 */
public final class StunConstants {

    /* STUN message header is 20 bytes */
    public static final int HEADER_SIZE = 20;

    /* STUN magic cookie (RFC 5389) */
    public static final int MAGIC_COOKIE = 0x2112A442;

    /* Binding method */
    public static final int METHOD_BINDING = 0x001;

    /* Attribute types */
    public static final int ATTR_MAPPED_ADDRESS     = 0x0001;
    public static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    public static final int ATTR_USERNAME           = 0x0006;
    public static final int ATTR_MESSAGE_INTEGRITY  = 0x0008;
    public static final int ATTR_ERROR_CODE         = 0x0009;
    public static final int ATTR_UNKNOWN_ATTRIBUTES = 0x000A;
    public static final int ATTR_REALM              = 0x0014;
    public static final int ATTR_NONCE              = 0x0015;
    public static final int ATTR_PRIORITY            = 0x0024;
    public static final int ATTR_USE_CANDIDATE      = 0x0025;
    public static final int ATTR_FINGERPRINT        = 0x8028;
    public static final int ATTR_ICE_CONTROLLED     = 0x8029;
    public static final int ATTR_ICE_CONTROLLING    = 0x802A;
    public static final int ATTR_SOFTWARE           = 0x8022;

    /* Address family */
    public static final int ADDR_IPV4 = 0x01;
    public static final int ADDR_IPV6 = 0x02;

    private StunConstants() {}
}
