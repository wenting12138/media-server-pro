package com.wenting.mediaserver.protocol.http.hls;

final class HlsSegment {

    private final long sequence;
    private final long durationMillis;
    private final byte[] bytes;

    HlsSegment(long sequence, long durationMillis, byte[] bytes) {
        this.sequence = sequence;
        this.durationMillis = durationMillis;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    long sequence() {
        return sequence;
    }

    long durationMillis() {
        return durationMillis;
    }

    byte[] bytes() {
        return bytes;
    }
}
