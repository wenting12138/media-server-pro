package com.wenting.mediaserver.protocol.webrtc.ingest;

import com.wenting.mediaserver.core.publish.report.RtpSequenceHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks short-lived RTP gaps and determines when Generic NACK should be sent.
 */
public final class NackGenerator {

    private static final int DEFAULT_MAX_TRACKED_GAP = 64;
    private static final long DEFAULT_INITIAL_DELAY_MS = 30L;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 40L;
    private static final int DEFAULT_MAX_RETRIES = 4;
    private static final long DEFAULT_EXPIRY_MS = 1200L;

    private final int maxTrackedGap;
    private final long initialDelayMs;
    private final long retryIntervalMs;
    private final int maxRetries;
    private final long expiryMs;
    private final Map<Integer, MissingSequence> pendingBySequence = new LinkedHashMap<Integer, MissingSequence>();

    private boolean initialized;
    private int highestSequenceNumber;

    public NackGenerator() {
        this(
                DEFAULT_MAX_TRACKED_GAP,
                DEFAULT_INITIAL_DELAY_MS,
                DEFAULT_RETRY_INTERVAL_MS,
                DEFAULT_MAX_RETRIES,
                DEFAULT_EXPIRY_MS
        );
    }

    NackGenerator(int maxTrackedGap, long initialDelayMs, long retryIntervalMs, int maxRetries, long expiryMs) {
        this.maxTrackedGap = Math.max(1, maxTrackedGap);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.retryIntervalMs = Math.max(1L, retryIntervalMs);
        this.maxRetries = Math.max(1, maxRetries);
        this.expiryMs = Math.max(this.retryIntervalMs, expiryMs);
    }

    public synchronized void onSequenceReceived(int sequenceNumber, long nowMs) {
        int normalized = sequenceNumber & 0xFFFF;
        pendingBySequence.remove(Integer.valueOf(normalized));
        if (!initialized) {
            initialized = true;
            highestSequenceNumber = normalized;
            pruneExpired(nowMs);
            return;
        }
        if (normalized == highestSequenceNumber || RtpSequenceHelper.isOlder(normalized, highestSequenceNumber)) {
            pruneExpired(nowMs);
            return;
        }
        int gap = RtpSequenceHelper.forwardDistance(highestSequenceNumber, normalized);
        if (gap > 1 && gap <= maxTrackedGap) {
            int missing = RtpSequenceHelper.next(highestSequenceNumber);
            while (missing != normalized) {
                pendingBySequence.putIfAbsent(Integer.valueOf(missing), new MissingSequence(nowMs));
                missing = RtpSequenceHelper.next(missing);
            }
        } else if (gap > maxTrackedGap) {
            pendingBySequence.clear();
        }
        highestSequenceNumber = normalized;
        pruneExpired(nowMs);
    }

    public synchronized List<Integer> pollDueNacks(long nowMs, int maxSequenceCount) {
        pruneExpired(nowMs);
        if (pendingBySequence.isEmpty() || maxSequenceCount <= 0) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<Integer>();
        Iterator<Map.Entry<Integer, MissingSequence>> iterator = pendingBySequence.entrySet().iterator();
        while (iterator.hasNext() && result.size() < maxSequenceCount) {
            Map.Entry<Integer, MissingSequence> entry = iterator.next();
            MissingSequence missing = entry.getValue();
            if (missing == null || !missing.isDue(nowMs, initialDelayMs, retryIntervalMs)) {
                continue;
            }
            result.add(entry.getKey());
            missing.retries++;
            missing.lastSentAtMs = nowMs;
            if (missing.retries >= maxRetries) {
                iterator.remove();
            }
        }
        return result;
    }

    private void pruneExpired(long nowMs) {
        Iterator<Map.Entry<Integer, MissingSequence>> iterator = pendingBySequence.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, MissingSequence> entry = iterator.next();
            MissingSequence missing = entry.getValue();
            if (missing == null || (nowMs - missing.firstSeenAtMs) > expiryMs) {
                iterator.remove();
            }
        }
    }


}
