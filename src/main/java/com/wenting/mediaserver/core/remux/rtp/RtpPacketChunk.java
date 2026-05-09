package com.wenting.mediaserver.core.remux.rtp;

public final class RtpPacketChunk {

    private final byte[] payload;
    private final boolean marker;

    public RtpPacketChunk(byte[] payload, boolean marker) {
        this.payload = payload == null ? new byte[0] : payload;
        this.marker = marker;
    }

    public byte[] payload() {
        return payload;
    }

    public boolean marker() {
        return marker;
    }
}
