package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.util.Arrays;

public final class DtlsClientHello {

    private final int version;
    private final byte[] recordSequence;
    private final byte[] random;
    private final int cipherSuiteCount;

    public DtlsClientHello(int version, byte[] recordSequence, byte[] random, int cipherSuiteCount) {
        this.version = version;
        this.recordSequence = copy(recordSequence);
        this.random = copy(random);
        this.cipherSuiteCount = cipherSuiteCount;
    }

    public int version() {
        return version;
    }

    public byte[] recordSequence() {
        return copy(recordSequence);
    }

    public byte[] random() {
        return copy(random);
    }

    public int cipherSuiteCount() {
        return cipherSuiteCount;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : Arrays.copyOf(value, value.length);
    }
}
