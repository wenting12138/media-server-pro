package com.wenting.mediaserver.core.publish.gop;

import com.wenting.mediaserver.core.publish.PublishStreamHelper;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory cache for one latest GOP window.
 */
public final class GopCache {

    private final int maxPackets;
    private final int maxBytes;
    private final long maxDurationMillis;
    private final List<InboundRtpPacket> packets = new ArrayList<InboundRtpPacket>();
    private final Map<String, Long> baseTimestampByTrackId = new LinkedHashMap<String, Long>();
    private int totalBytes;

    public GopCache(int maxPackets, int maxBytes) {
        this(maxPackets, maxBytes, Long.MAX_VALUE);
    }

    public GopCache(int maxPackets, int maxBytes, long maxDurationMillis) {
        if (maxPackets <= 0) {
            throw new IllegalArgumentException("maxPackets must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxDurationMillis <= 0) {
            throw new IllegalArgumentException("maxDurationMillis must be positive");
        }
        this.maxPackets = maxPackets;
        this.maxBytes = maxBytes;
        this.maxDurationMillis = maxDurationMillis;
    }

    public void startNewGop(InboundRtpPacket packet) {
        startNewGop(packet, null);
    }

    public void startNewGop(InboundRtpPacket packet, Long timestamp) {
        clear();
        append(packet, timestamp);
    }

    public boolean append(InboundRtpPacket packet) {
        return append(packet, null);
    }

    public boolean append(InboundRtpPacket packet, Long timestamp) {
        if (packet == null) {
            return false;
        }
        int packetBytes = packet.frame().payloadLength();
        if (packetBytes > maxBytes) {
            clear();
            return false;
        }
        if (!withinTimestampWindow(packet, timestamp)) {
            return false;
        }
        if (wouldOverflow(packetBytes)) {
            clear();
            return false;
        }
        packets.add(packet);
        totalBytes += packetBytes;
        recordTrackTimestamp(packet, timestamp);
        return true;
    }

    public List<InboundRtpPacket> snapshot() {
        return Collections.unmodifiableList(new ArrayList<InboundRtpPacket>(packets));
    }

    public boolean isEmpty() {
        return packets.isEmpty();
    }

    public int packetCount() {
        return packets.size();
    }

    public int totalBytes() {
        return totalBytes;
    }

    public void clear() {
        packets.clear();
        baseTimestampByTrackId.clear();
        totalBytes = 0;
    }

    private boolean wouldOverflow(int packetBytes) {
        return packets.size() >= maxPackets || totalBytes + packetBytes > maxBytes;
    }

    private boolean withinTimestampWindow(InboundRtpPacket packet, Long timestamp) {
        if (timestamp == null) {
            return true;
        }
        String trackId = PublishStreamHelper.trackLabel(packet.frame().trackId());
        Long baseTimestamp = baseTimestampByTrackId.get(trackId);
        if (baseTimestamp == null) {
            return true;
        }
        long delta = timestamp.longValue() - baseTimestamp.longValue();
        long maxTimestampDelta = maxTimestampDelta(packet.clockRate());
        return delta >= 0 && (maxTimestampDelta == Long.MAX_VALUE || delta <= maxTimestampDelta);
    }

    private void recordTrackTimestamp(InboundRtpPacket packet, Long timestamp) {
        if (timestamp == null) {
            return;
        }
        String trackId = PublishStreamHelper.trackLabel(packet.frame().trackId());
        if (!baseTimestampByTrackId.containsKey(trackId)) {
            baseTimestampByTrackId.put(trackId, timestamp);
        }
    }

    private long maxTimestampDelta(int clockRate) {
        if (clockRate <= 0) {
            return Long.MAX_VALUE;
        }
        long delta = (clockRate * maxDurationMillis) / 1000L;
        return delta <= 0 ? 1L : delta;
    }
}
