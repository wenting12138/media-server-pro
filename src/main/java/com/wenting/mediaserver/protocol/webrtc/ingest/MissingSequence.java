package com.wenting.mediaserver.protocol.webrtc.ingest;

public final class MissingSequence {
    public final long firstSeenAtMs;
    public long lastSentAtMs;
    public int retries;
    public MissingSequence(long firstSeenAtMs) {
        this.firstSeenAtMs = firstSeenAtMs;
    }
    public boolean isDue(long nowMs, long initialDelayMs, long retryIntervalMs) {
        if ((nowMs - firstSeenAtMs) < initialDelayMs) {
            return false;
        }
        return lastSentAtMs == 0L || (nowMs - lastSentAtMs) >= retryIntervalMs;
    }
}