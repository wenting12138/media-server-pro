package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcPlayHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnWebRtcAnswerForExistingPublishedStream() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        registry.registerPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam01"), stream);
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcPlayHandler(registry, sessionManager, 18081));

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(offerWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(0, root.get("code").asInt());
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=video"));
        assertTrue(answerSdp.contains("a=sendonly"));
        assertTrue(answerSdp.contains("a=fingerprint:sha-256 "));
        assertTrue(answerSdp.contains("a=ice-ufrag:"));
        assertTrue(answerSdp.contains("a=rtpmap:97 H264/90000"));
        assertTrue(answerSdp.contains("a=candidate:1 1 udp "));
        assertTrue(answerSdp.contains(" 18081 typ host"));
        assertEquals(1, stream.subscriberCount());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldUseHttpHostHeaderForCandidateWhenServerBindsWildcardAddress() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        registry.registerPublishedStream(
                new StreamKey(StreamProtocol.RTMP, "live", "cam-host"),
                new DefaultPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam-host"))
        );
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcPlayHandler(registry, sessionManager, 18081));

        DefaultFullHttpRequest request = request("{\"app\":\"live\",\"stream\":\"cam-host\",\"sdp\":\"" + escapeJson(offerWithH264()) + "\"}");
        request.headers().set("Host", "192.168.1.10:18080");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("c=IN IP4 192.168.1.10"));
        assertTrue(answerSdp.contains("a=candidate:1 1 udp 2130706431 192.168.1.10 18081 typ host"));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectOfferWithoutH264Video() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        registry.registerPublishedStream(
                new StreamKey(StreamProtocol.RTMP, "live", "cam02"),
                new DefaultPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam02"))
        );
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcPlayHandler(registry, new WebRtcSessionManager(), 18081));

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam02\",\"sdp\":\"" + escapeJson(offerWithoutH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(400, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(-1, root.get("code").asInt());
        assertTrue(root.get("msg").asText().contains("H264"));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturnNotFoundWhenStreamMissing() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcPlayHandler(new StreamRegistry(), new WebRtcSessionManager(), 18081));

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"missing\",\"sdp\":\"" + escapeJson(offerWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(404, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(-1, root.get("code").asInt());
        response.release();
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest request(String json) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/webrtc/play",
                io.netty.buffer.Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        request.headers().set("Content-Type", "application/json");
        request.headers().setInt("Content-Length", request.content().readableBytes());
        return request;
    }

    private static String offerWithH264() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=rtpmap:96 VP8/90000\r\n"
                + "a=rtpmap:97 H264/90000\r\n";
    }

    private static String offerWithoutH264() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "a=mid:0\r\n"
                + "a=rtpmap:96 VP8/90000\r\n";
    }

    private static String escapeJson(String sdp) {
        return sdp.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }
}
