package com.wenting.mediaserver.core.stats;

import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.model.StreamKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTrafficStatsService implements TrafficStatsService {

    private final TrafficCounter globalCounter = new TrafficCounter();
    private final Map<TrafficProtocol, TrafficCounter> protocolCounters = new ConcurrentHashMap<TrafficProtocol, TrafficCounter>();
    private final Map<StreamKey, TrafficCounter> streamCounters = new ConcurrentHashMap<StreamKey, TrafficCounter>();
    private final Map<TrafficStatsKey, TrafficCounter> trackCounters = new ConcurrentHashMap<TrafficStatsKey, TrafficCounter>();

    @Override
    public void record(TrafficEvent event) {
        if (event == null) {
            return;
        }
        globalCounter.add(event.bytes(), event.packets(), event.timestampMillis());
        if (event.protocol() != null) {
            counterForProtocol(event.protocol()).add(event.bytes(), event.packets(), event.timestampMillis());
        }
        if (event.streamKey() != null) {
            counterForStream(event.streamKey()).add(event.bytes(), event.packets(), event.timestampMillis());
            counterForTrack(event.streamKey(), event.trackId()).add(event.bytes(), event.packets(), event.timestampMillis());
        }
    }

    @Override
    public TrafficSnapshot globalSnapshot() {
        return globalCounter.snapshot();
    }

    @Override
    public TrafficSnapshot protocolSnapshot(TrafficProtocol protocol) {
        TrafficCounter counter = protocol == null ? null : protocolCounters.get(protocol);
        return counter == null ? new TrafficSnapshot(0, 0, 0) : counter.snapshot();
    }

    @Override
    public TrafficSnapshot streamSnapshot(StreamKey streamKey) {
        TrafficCounter counter = streamKey == null ? null : streamCounters.get(streamKey);
        return counter == null ? new TrafficSnapshot(0, 0, 0) : counter.snapshot();
    }

    @Override
    public TrafficSnapshot trackSnapshot(StreamKey streamKey, String trackId) {
        TrafficCounter counter = streamKey == null ? null : trackCounters.get(new TrafficStatsKey(streamKey, normalizeTrackId(trackId)));
        return counter == null ? new TrafficSnapshot(0, 0, 0) : counter.snapshot();
    }

    private TrafficCounter counterForStream(StreamKey streamKey) {
        TrafficCounter counter = streamCounters.get(streamKey);
        if (counter != null) {
            return counter;
        }
        TrafficCounter created = new TrafficCounter();
        TrafficCounter existing = streamCounters.putIfAbsent(streamKey, created);
        return existing == null ? created : existing;
    }

    private TrafficCounter counterForProtocol(TrafficProtocol protocol) {
        TrafficCounter counter = protocolCounters.get(protocol);
        if (counter != null) {
            return counter;
        }
        TrafficCounter created = new TrafficCounter();
        TrafficCounter existing = protocolCounters.putIfAbsent(protocol, created);
        return existing == null ? created : existing;
    }

    private TrafficCounter counterForTrack(StreamKey streamKey, String trackId) {
        TrafficStatsKey key = new TrafficStatsKey(streamKey, normalizeTrackId(trackId));
        TrafficCounter counter = trackCounters.get(key);
        if (counter != null) {
            return counter;
        }
        TrafficCounter created = new TrafficCounter();
        TrafficCounter existing = trackCounters.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }
}
