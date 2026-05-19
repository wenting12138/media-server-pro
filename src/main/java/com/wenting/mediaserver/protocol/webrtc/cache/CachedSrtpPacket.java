package com.wenting.mediaserver.protocol.webrtc.cache;

public final class CachedSrtpPacket {
    public final byte[] packet;
    public final long sentAtMs;
    public CachedSrtpPacket(byte[] packet, long sentAtMs) {
        this.packet = packet;
        this.sentAtMs = sentAtMs;
    }
}