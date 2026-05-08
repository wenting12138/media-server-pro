package com.wenting.mediaserver.core.stats;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.traffic.TrafficDirection;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryTrafficStatsServiceTest {

    @Test
    void shouldAggregateGlobalStreamAndTrackTraffic() {
        InMemoryTrafficStatsService service = new InMemoryTrafficStatsService();
        StreamKey key = new StreamKey(StreamProtocol.RTSP, "live", "cam01");

        service.record(new TrafficEvent(
                TrafficDirection.INBOUND,
                TrafficProtocol.RTP_UDP,
                key,
                "session-1",
                "trackID=0",
                1200,
                1,
                1000
        ));
        service.record(new TrafficEvent(
                TrafficDirection.INBOUND,
                TrafficProtocol.RTP_UDP,
                key,
                "session-1",
                "trackID=0",
                800,
                1,
                2000
        ));

        assertEquals(2000, service.globalSnapshot().bytes());
        assertEquals(2, service.globalSnapshot().packets());
        assertEquals(2000, service.protocolSnapshot(TrafficProtocol.RTP_UDP).bytes());
        assertEquals(2, service.protocolSnapshot(TrafficProtocol.RTP_UDP).packets());
        assertEquals(2000, service.streamSnapshot(key).bytes());
        assertEquals(2, service.streamSnapshot(key).packets());
        assertEquals(2000, service.trackSnapshot(key, "trackID=0").bytes());
        assertEquals(2, service.trackSnapshot(key, "trackID=0").packets());
    }
}
