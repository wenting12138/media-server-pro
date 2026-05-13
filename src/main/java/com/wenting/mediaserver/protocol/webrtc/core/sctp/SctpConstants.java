package com.wenting.mediaserver.protocol.webrtc.core.sctp;

/**
 * SCTP protocol constants (RFC 4960) and WebRTC PPID values (RFC 8831).
 */
public final class SctpConstants {

    // ---- Chunk Types ----
    public static final int DATA            = 0;
    public static final int INIT            = 1;
    public static final int INIT_ACK        = 2;
    public static final int SACK            = 3;
    public static final int HEARTBEAT       = 4;
    public static final int HEARTBEAT_ACK   = 5;
    public static final int ABORT           = 6;
    public static final int SHUTDOWN        = 7;
    public static final int SHUTDOWN_ACK    = 8;
    public static final int COOKIE_ECHO     = 10;
    public static final int COOKIE_ACK      = 11;

    // ---- DATA chunk flags -//
    public static final byte DATA_FLAG_UNORDERED = 0x04; // U bit
    public static final byte DATA_FLAG_BEGIN     = 0x02; // B bit
    public static final byte DATA_FLAG_END       = 0x01; // E bit

    // ---- PPID (Payload Protocol Identifier) for WebRTC DataChannel ----
    public static final int PPID_WEBRTC_STRING  = 50;
    public static final int PPID_WEBRTC_BINARY  = 51;
    public static final int PPID_WEBRTC_STRING_PARTIAL = 52;
    public static final int PPID_WEBRTC_BINARY_PARTIAL = 53;

    // ---- SCTP Header Offsets ----
    public static final int HEADER_SIZE = 12;

    // ---- SCTP Chunk Header Offsets ----
    public static final int CHUNK_HEADER_SIZE = 4;

    // ---- Default Stream Configuration ----
    public static final int DEFAULT_OS = 64;  // Number of outbound streams
    public static final int DEFAULT_MISS = 64; // Number of inbound streams
    public static final int DEFAULT_ADVERTISED_RWND = 65536;

    // ---- DataChannel Protocol Constants (RFC 8832) ----
    public static final int DATA_CHANNEL_OPEN          = 0x03;
    public static final int DATA_CHANNEL_ACK            = 0x02;

    // ---- DataChannel Types ----
    public static final int DATA_CHANNEL_RELIABLE              = 0x00;
    public static final int DATA_CHANNEL_RELIABLE_UNORDERED   = 0x80;
    public static final int DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT   = 0x01;
    public static final int DATA_CHANNEL_PARTIAL_RELIABLE_TIMED    = 0x02;

    private SctpConstants() {}
}
