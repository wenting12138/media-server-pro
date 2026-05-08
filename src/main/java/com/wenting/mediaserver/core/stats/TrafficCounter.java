package com.wenting.mediaserver.core.stats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TrafficCounter {

    private final LongAdder bytes = new LongAdder();
    private final LongAdder packets = new LongAdder();
    private final AtomicLong lastUpdatedAtMillis = new AtomicLong();

    public void add(long byteCount, long packetCount, long timestampMillis) {
        bytes.add(byteCount);
        packets.add(packetCount);
        lastUpdatedAtMillis.set(timestampMillis);
    }

    public TrafficSnapshot snapshot() {
        return new TrafficSnapshot(bytes.sum(), packets.sum(), lastUpdatedAtMillis.get());
    }
}
