package com.wenting.mediaserver.core.stats;

import com.wenting.mediaserver.core.enums.traffic.TrafficDirection;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.model.StreamKey;

public final class TrafficEvent {

    private final TrafficDirection direction;
    private final TrafficProtocol protocol;
    private final StreamKey streamKey;
    private final String sessionId;
    private final String trackId;
    private final long bytes;
    private final long packets;
    private final long timestampMillis;

    public TrafficEvent(
            TrafficDirection direction,
            TrafficProtocol protocol,
            StreamKey streamKey,
            String sessionId,
            String trackId,
            long bytes,
            long packets,
            long timestampMillis
    ) {
        this.direction = direction;
        this.protocol = protocol;
        this.streamKey = streamKey;
        this.sessionId = sessionId;
        this.trackId = trackId == null ? "" : trackId;
        this.bytes = bytes;
        this.packets = packets;
        this.timestampMillis = timestampMillis;
    }

    public TrafficDirection direction() {
        return direction;
    }

    public TrafficProtocol protocol() {
        return protocol;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String sessionId() {
        return sessionId;
    }

    public String trackId() {
        return trackId;
    }

    public long bytes() {
        return bytes;
    }

    public long packets() {
        return packets;
    }

    public long timestampMillis() {
        return timestampMillis;
    }
}
