package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RtspHelperTest {

    @Test
    void shouldParseInterleavedTransportHeader() {
        RtspTransport transport = RtspHelper.parseTransport("RTP/AVP/TCP;unicast;interleaved=0-1");

        assertEquals(RtspTransportMode.RTP_TCP_INTERLEAVED, transport.mode());
        assertEquals(Integer.valueOf(0), transport.interleavedRtpChannel());
        assertEquals(Integer.valueOf(1), transport.interleavedRtcpChannel());
    }

    @Test
    void shouldParseUdpTransportHeader() {
        RtspTransport transport = RtspHelper.parseTransport("RTP/AVP;unicast;client_port=5000-5001");

        assertEquals(RtspTransportMode.RTP_UDP, transport.mode());
        assertEquals(Integer.valueOf(5000), transport.clientRtpPort());
        assertEquals(Integer.valueOf(5001), transport.clientRtcpPort());
    }

    @Test
    void shouldParseTrackIdFromSetupUri() {
        assertEquals(
                "streamid=0",
                RtspHelper.parseTrackId(
                        "rtsp://127.0.0.1:1554/live/123456/streamid=0",
                        RtspHelper.parseStreamKey("rtsp://127.0.0.1:1554/live/123456")
                )
        );
    }
}
