package com.wenting.mediaserver.core.stats;

import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.model.StreamKey;

public interface TrafficStatsService {

    void record(TrafficEvent event);

    TrafficSnapshot globalSnapshot();

    TrafficSnapshot protocolSnapshot(TrafficProtocol protocol);

    TrafficSnapshot streamSnapshot(StreamKey streamKey);

    TrafficSnapshot trackSnapshot(StreamKey streamKey, String trackId);
}
