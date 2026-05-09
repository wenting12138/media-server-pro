package com.wenting.mediaserver.protocol.webrtc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcOfferParserTest {

    @Test
    void shouldExtractVideoMidAndH264PayloadType() {
        WebRtcOfferSummary summary = new WebRtcOfferParser().parse(
                "v=0\r\n"
                        + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                        + "s=-\r\n"
                        + "t=0 0\r\n"
                        + "m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n"
                        + "a=mid:video0\r\n"
                        + "a=rtpmap:96 VP8/90000\r\n"
                        + "a=rtpmap:97 H264/90000\r\n"
        );

        assertTrue(summary.hasVideo());
        assertTrue(summary.supportsH264Video());
        assertEquals("video0", summary.videoMid());
        assertEquals(Integer.valueOf(97), summary.h264PayloadType());
    }
}
