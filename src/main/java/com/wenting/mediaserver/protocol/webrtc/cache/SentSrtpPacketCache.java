package com.wenting.mediaserver.protocol.webrtc.cache;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SentSrtpPacketCache {
    public final int capacity;
    public final long maxAgeMs;
    public final LinkedHashMap<Integer, CachedSrtpPacket> packets = new LinkedHashMap<Integer, CachedSrtpPacket>(128, 0.75f, true);
    public SentSrtpPacketCache(int capacity, long maxAgeMs) {
        this.capacity = Math.max(1, capacity);
        this.maxAgeMs = Math.max(1L, maxAgeMs);
    }
    public synchronized void put(int sequenceNumber, byte[] packet, long nowMs) {
        if (packet == null || packet.length == 0) {
            return;
        }
        evictExpired(nowMs);
        packets.put(Integer.valueOf(sequenceNumber & 0xFFFF), new CachedSrtpPacket(Arrays.copyOf(packet, packet.length), nowMs));
        while (packets.size() > capacity) {
            Integer eldest = packets.keySet().iterator().next();
            packets.remove(eldest);
        }
    }
    public synchronized byte[] get(int sequenceNumber, long nowMs) {
        evictExpired(nowMs);
        CachedSrtpPacket packet = packets.get(Integer.valueOf(sequenceNumber & 0xFFFF));
        if (packet == null) {
            return null;
        }
        return Arrays.copyOf(packet.packet, packet.packet.length);
    }
    private void evictExpired(long nowMs) {
        while (!packets.isEmpty()) {
            Map.Entry<Integer, CachedSrtpPacket> eldest = packets.entrySet().iterator().next();
            if (nowMs - eldest.getValue().sentAtMs <= maxAgeMs) {
                return;
            }
            packets.remove(eldest.getKey());
        }
    }
}
