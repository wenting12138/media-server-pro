package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRouterHandler;
import com.wenting.mediaserver.protocol.webrtc.ServerWebRtcPeerSession;
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
        DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, publishedStream);
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
        assertTrue(root.get("data").hasNonNull("sessionId"));
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=video"));
        assertTrue(!answerSdp.contains("m=application"));
        assertTrue(!answerSdp.contains("%"));
        assertTrue(answerSdp.contains("a=group:BUNDLE 0\r\n"));
        assertTrue(answerSdp.contains("a=mid:0\r\n"));
        assertTrue(!answerSdp.contains("a=mid:1\r\n"));
        assertTrue(answerSdp.contains("a=sendonly\r\n"));
        assertTrue(answerSdp.contains("a=rtcp-mux\r\n"));
        assertTrue(answerSdp.contains("a=setup:passive\r\n"));
        assertTrue(answerSdp.contains("a=msid:0 webrtc-video-cam01\r\n"));
        assertTrue(answerSdp.contains("a=fingerprint:sha-256 "));
        assertTrue(answerSdp.contains("a=ice-ufrag:"));
        assertTrue(answerSdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(answerSdp.contains("192.168.3.52 18081"));
        assertEquals(1, sessionManager.count());
        assertEquals(1, publishedStream.subscriberCount());
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
    void shouldPreferPcmuWhenOfferContainsAudio() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, publishedStream);
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPlayHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(offerWithAudioAndH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=audio 9 UDP/TLS/RTP/SAVPF 0"));
        assertTrue(answerSdp.contains("a=rtpmap:0 PCMU/8000"));
        assertTrue(answerSdp.contains("a=msid:0 webrtc-audio-cam01"));
        assertTrue(answerSdp.contains("m=video 9 UDP/TLS/RTP/SAVPF 96"));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldRemoveSubscriberWhenPeerConnectionCloses() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, publishedStream);
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
        assertEquals(1, sessionManager.count());
        assertEquals(1, publishedStream.subscriberCount());

        ServerWebRtcPeerSession session = sessionManager.sessions().iterator().next();
        session.peerConnection().close();

        assertEquals(0, sessionManager.count());
        assertEquals(0, publishedStream.subscriberCount());
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
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
                + "a=setup:actpass\r\n"
                + "a=recvonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static String offerWithAudioAndH264() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0 1\r\n"
                + "a=ice-ufrag:abcd\r\n"
                + "a=ice-pwd:abcdefghijklmnopqrstuv\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\n"
                + "m=audio 9 UDP/TLS/RTP/SAVPF 111 0 8\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=setup:actpass\r\n"
                + "a=recvonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:111 opus/48000/2\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=rtpmap:8 PCMA/8000\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:1\r\n"
                + "a=setup:actpass\r\n"
                + "a=recvonly\r\n"
                + "a=rtcp-mux\r\n"
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
