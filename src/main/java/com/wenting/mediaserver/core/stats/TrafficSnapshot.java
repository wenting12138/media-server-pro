package com.wenting.mediaserver.core.stats;

public final class TrafficSnapshot {

    private final long bytes;
    private final long packets;
    private final long lastUpdatedAtMillis;

    public TrafficSnapshot(long bytes, long packets, long lastUpdatedAtMillis) {
        this.bytes = bytes;
        this.packets = packets;
        this.lastUpdatedAtMillis = lastUpdatedAtMillis;
    }

    public long bytes() {
        return bytes;
    }

    public long packets() {
        return packets;
    }

    public long lastUpdatedAtMillis() {
        return lastUpdatedAtMillis;
    }
}
