package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRouterHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcPlayHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnWebRtcAnswerAndRegisterSessionForExistingStream() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        registry.registerPublishedStream(streamKey, new DefaultPublishedStream(streamKey));
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPlayHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(offerWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(0, root.get("code").asInt());
        assertEquals("answer", root.get("data").get("type").asText());
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=video"));
        assertTrue(answerSdp.contains("a=fingerprint:sha-256 "));
        assertTrue(answerSdp.contains("a=ice-ufrag:"));
        assertTrue(answerSdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(answerSdp.contains("192.168.3.52 18081"));
        assertEquals(1, sessionManager.count());
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldReturnNotFoundForMissingStream() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPlayHandler(
                        new StreamRegistry(),
                        new WebRtcSessionManager(),
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"missing\",\"sdp\":\"" + escapeJson(offerWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(404, response.status().code());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturnBadRequestWhenPayloadMissingFields() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPlayHandler(
                        new StreamRegistry(),
                        new WebRtcSessionManager(),
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(400, response.status().code());
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
                + "a=ice-ufrag:abcd\r\n"
                + "a=ice-pwd:abcdefghijklmnopqrstuv\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=sendrecv\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class NoopDatagramIoSender implements DatagramIoSender {
        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
