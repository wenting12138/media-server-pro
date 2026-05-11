package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.util.Arrays;

public final class SrtpKeyingMaterial {

    public static final int KEY_LENGTH = 16;
    public static final int SALT_LENGTH = 14;
    public static final int TOTAL_LENGTH = KEY_LENGTH * 2 + SALT_LENGTH * 2;

    private final byte[] clientWriteKey;
    private final byte[] serverWriteKey;
    private final byte[] clientWriteSalt;
    private final byte[] serverWriteSalt;
    private final byte[] raw;

    public SrtpKeyingMaterial(
            byte[] clientWriteKey,
            byte[] serverWriteKey,
            byte[] clientWriteSalt,
            byte[] serverWriteSalt,
            byte[] raw
    ) {
        this.clientWriteKey = copy(clientWriteKey);
        this.serverWriteKey = copy(serverWriteKey);
        this.clientWriteSalt = copy(clientWriteSalt);
        this.serverWriteSalt = copy(serverWriteSalt);
        this.raw = copy(raw);
    }

    public byte[] clientWriteKey() {
        return copy(clientWriteKey);
    }

    public byte[] serverWriteKey() {
        return copy(serverWriteKey);
    }

    public byte[] clientWriteSalt() {
        return copy(clientWriteSalt);
    }

    public byte[] serverWriteSalt() {
        return copy(serverWriteSalt);
    }

    public byte[] raw() {
        return copy(raw);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : Arrays.copyOf(value, value.length);
    }
}
