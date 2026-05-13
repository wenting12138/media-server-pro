package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpBuilder;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.Attribute;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.MediaDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * SDP parse/build round-trip tests.
 */
public class SdpTest {

    private static final String SAMPLE_SDP =
        "v=0\r\n" +
        "o=- 123456 2 IN IP4 127.0.0.1\r\n" +
        "s=-\r\n" +
        "t=0 0\r\n" +
        "a=group:BUNDLE 0 1\r\n" +
        "a=ice-ufrag:testUfrag\r\n" +
        "a=ice-pwd:testPwd\r\n" +
        "a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF\r\n" +
        "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
        "c=IN IP4 0.0.0.0\r\n" +
        "a=mid:0\r\n" +
        "a=rtpmap:111 opus/48000/2\r\n" +
        "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
        "c=IN IP4 0.0.0.0\r\n" +
        "a=mid:1\r\n" +
        "a=rtpmap:96 H264/90000\r\n";

    @Test
    public void testParseBasicSdp() {
        SdpDescription sdp = SdpParser.parse(SAMPLE_SDP);

        assertEquals(0, sdp.getVersion());
        assertEquals("testUfrag", sdp.getIceUfrag());
        assertEquals("testPwd", sdp.getIcePwd());
        assertEquals("AA:BB:CC:DD:EE:FF", sdp.getFingerprint());
    }

    @Test
    public void testParseMediaDescriptions() {
        SdpDescription sdp = SdpParser.parse(SAMPLE_SDP);
        List<MediaDescription> mediaList = sdp.getMediaDescriptions();

        assertEquals("Should have 2 media sections", 2, mediaList.size());

        MediaDescription audio = mediaList.get(0);
        assertEquals("audio", audio.mediaType);
        assertEquals(9, audio.port);
        assertEquals("UDP/TLS/RTP/SAVPF", audio.protocol);
        assertTrue("Audio should have payload 111", audio.payloadTypes.contains(111));
        assertEquals("0", audio.getMid());

        MediaDescription video = mediaList.get(1);
        assertEquals("video", video.mediaType);
        assertEquals("1", video.getMid());
        assertTrue("Video should have payload 96", video.payloadTypes.contains(96));
    }

    @Test
    public void testParseSessionAttributes() {
        SdpDescription sdp = SdpParser.parse(SAMPLE_SDP);
        List<Attribute> attrs = sdp.getSessionAttributes();

        boolean hasBundle = false;
        for (Attribute a : attrs) {
            if ("group".equals(a.key) && "BUNDLE 0 1".equals(a.value)) {
                hasBundle = true;
                break;
            }
        }
        assertTrue("Should have BUNDLE attribute", hasBundle);
    }

    @Test
    public void testBuilderRoundTrip() {
        String sdpText = new SdpBuilder()
            .setOrigin("-", 123456, "127.0.0.1")
            .addWebrtcSessionAttributes("ufrag1", "pwd1", "11:22:33")
            .addMedia("audio", 9, "UDP/TLS/RTP/SAVPF", 111)
                .addAttribute("mid", "0")
                .addAttribute("rtpmap", "111 opus/48000/2")
                .done()
            .addMedia("video", 9, "UDP/TLS/RTP/SAVPF", 96, 97)
                .addAttribute("mid", "1")
                .addAttribute("rtpmap", "96 H264/90000")
                .done()
            .build();

        // 验证输出包含必要字段
        assertTrue("Should contain v=0", sdpText.contains("v=0"));
        assertTrue("Should contain ice-ufrag", sdpText.contains("ice-ufrag:ufrag1"));
        assertTrue("Should contain fingerprint", sdpText.contains("fingerprint:sha-256 11:22:33"));
        assertTrue("Should contain audio media", sdpText.contains("m=audio"));
        assertTrue("Should contain video media", sdpText.contains("m=video"));
        assertTrue("Should contain BUNDLE", sdpText.contains("BUNDLE 0 1"));
        assertTrue("Should contain rtpmap", sdpText.contains("rtpmap:96 H264/90000"));

        // Round-trip: 解析回去
        SdpDescription parsed = SdpParser.parse(sdpText);
        assertEquals("ufrag1", parsed.getIceUfrag());
        assertEquals("pwd1", parsed.getIcePwd());
        assertEquals("11:22:33", parsed.getFingerprint());
        assertEquals(2, parsed.getMediaDescriptions().size());
    }

    @Test
    public void testParseWithCandidateAttribute() {
        String sdpWithCandidate =
            "v=0\r\n" +
            "o=- 0 2 IN IP4 127.0.0.1\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=ice-ufrag:test\r\n" +
            "a=ice-pwd:test\r\n" +
            "a=fingerprint:sha-256 AA:BB\r\n" +
            "m=application 9 DTLS/SCTP 5000\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=mid:0\r\n" +
            "a=candidate:1 1 UDP 2130706431 192.168.1.10 5000 typ host\r\n";

        SdpDescription sdp = SdpParser.parse(sdpWithCandidate);
        assertEquals(1, sdp.getMediaDescriptions().size());

        MediaDescription app = sdp.getMediaDescriptions().get(0);
        assertEquals("application", app.mediaType);
        assertEquals("DTLS/SCTP", app.protocol);

        // 验证 candidate 属性被解析
        boolean hasCandidate = false;
        for (Attribute a : app.attributes) {
            if ("candidate".equals(a.key)
                && a.value.contains("192.168.1.10")) {
                hasCandidate = true;
                break;
            }
        }
        assertTrue("Should contain candidate for 192.168.1.10", hasCandidate);
    }

    @Test
    public void testBuilderWithCandidate() {
        String sdpText = new SdpBuilder()
            .setOrigin("-", 1, "127.0.0.1")
            .addWebrtcSessionAttributes("u", "p", "FF:EE")
            .addMedia("audio", 5000, "UDP/TLS/RTP/SAVPF", 111)
                .addAttribute("mid", "0")
                .addCandidate(new SdpBuilder.IceCandidateInfo(
                    "1", 1, "UDP", 2130706431L,
                    "192.168.1.10", 5000, "host"))
                .done()
            .build();

        assertTrue("Should include a=candidate line",
            sdpText.contains("a=candidate:1 1 UDP 2130706431 192.168.1.10 5000 typ host"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseEmptySdp() {
        SdpParser.parse("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseMissingOrigin() {
        SdpParser.parse("v=0\r\ns=-\r\nt=0 0\r\n");
    }

    @Test
    public void testAttributeFlag() {
        Attribute flag = Attribute.flag("recvonly");
        assertEquals("recvonly", flag.key);
        assertNull(flag.value);
    }
}
