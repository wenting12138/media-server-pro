package com.wenting.mediaserver.core.model.sdp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SdpParserTest {

    @Test
    void shouldParseRtspAnnounceSdp() {
        String sdp = ""
                + "v=0\n"
                + "o=- 0 0 IN IP4 127.0.0.1\n"
                + "s=No Name\n"
                + "c=IN IP4 127.0.0.1\n"
                + "t=0 0\n"
                + "a=tool:libavformat 60.17.100\n"
                + "m=video 0 RTP/AVP 96\n"
                + "b=AS:2500\n"
                + "a=rtpmap:96 H264/90000\n"
                + "a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z3oAH7y0AoAt0IACiwqAmJaAR4wZUA==,aM48gA==; profile-level-id=7A001F\n"
                + "a=control:streamid=0\n"
                + "m=audio 0 RTP/AVP 97\n"
                + "b=AS:128\n"
                + "a=rtpmap:97 MPEG4-GENERIC/48000/2\n"
                + "a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; config=119056E500\n"
                + "a=control:streamid=1\n";

        SdpSessionDescription description = new SdpParser().parse(sdp);

        assertEquals("0", description.version());
        assertEquals("- 0 0 IN IP4 127.0.0.1", description.origin());
        assertEquals("No Name", description.sessionName());
        assertEquals("IN IP4 127.0.0.1", description.connection());
        assertEquals("0 0", description.timing());
        assertEquals("libavformat 60.17.100", description.attributes().get("tool"));
        assertEquals(2, description.mediaDescriptions().size());

        SdpMediaDescription video = description.firstMedia("video");
        assertNotNull(video);
        assertEquals(0, video.port());
        assertEquals("RTP/AVP", video.transportProtocol());
        assertEquals("96", video.formats().get(0));
        assertEquals(Integer.valueOf(2500), video.bandwidthAsKbps());
        assertEquals(Integer.valueOf(96), video.payloadType());
        assertEquals("H264", video.codecName());
        assertEquals(Integer.valueOf(90000), video.clockRate());
        assertEquals("streamid=0", video.control());
        assertEquals("1", video.fmtpParameters().get("packetization-mode"));
        assertEquals("7A001F", video.fmtpParameters().get("profile-level-id"));

        SdpMediaDescription audio = description.firstMedia("audio");
        assertNotNull(audio);
        assertEquals(Integer.valueOf(128), audio.bandwidthAsKbps());
        assertEquals(Integer.valueOf(97), audio.payloadType());
        assertEquals("MPEG4-GENERIC", audio.codecName());
        assertEquals(Integer.valueOf(48000), audio.clockRate());
        assertEquals(Integer.valueOf(2), audio.channels());
        assertEquals("streamid=1", audio.control());
        assertEquals("AAC-hbr", audio.fmtpParameters().get("mode"));
        assertEquals("119056E500", audio.fmtpParameters().get("config"));
    }
}
