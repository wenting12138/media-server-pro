package com.wenting.mediaserver.core.stats;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.Objects;

public final class TrafficStatsKey {

    private final StreamKey streamKey;
    private final String trackId;

    public TrafficStatsKey(StreamKey streamKey, String trackId) {
        this.streamKey = streamKey;
        this.trackId = trackId == null ? "" : trackId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String trackId() {
        return trackId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TrafficStatsKey that = (TrafficStatsKey) o;
        return Objects.equals(streamKey, that.streamKey) && Objects.equals(trackId, that.trackId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamKey, trackId);
    }
}
