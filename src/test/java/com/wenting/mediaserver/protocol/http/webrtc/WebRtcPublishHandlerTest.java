package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRouterHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpReceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcPublishHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnAnswerAndDelayPublishedStreamRegistrationUntilConnected() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(0, root.get("code").asInt());
        assertEquals("answer", root.get("data").get("type").asText());
        assertTrue(root.get("data").hasNonNull("sessionId"));
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=video 9 UDP/TLS/RTP/SAVPF 96"));
        assertTrue(answerSdp.contains("a=recvonly\r\n"));
        assertTrue(answerSdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(answerSdp.contains("a=setup:passive\r\n"));
        assertEquals(1, sessionManager.count());

        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam01");
        assertNull(registry.findPublishedStream(streamKey));

        WebRtcPublishPeerSession session = sessionManager.sessions().iterator().next();
        emitConnectionState(session.peerConnection(), RTCPeerConnection.ConnectionState.CONNECTED);

        IPublishedStream stream = registry.findPublishedStream(streamKey);
        assertNotNull(stream);

        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldAcceptOptionalOpusAudioAlongsideH264Video() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam02\",\"sdp\":\"" + escapeJson(publishOfferWithH264AndOpus()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(0, root.get("code").asInt());
        String answerSdp = root.get("data").get("sdp").asText();
        assertTrue(answerSdp.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111"));
        assertTrue(answerSdp.contains("a=rtpmap:111 opus/48000/2"));
        assertTrue(answerSdp.contains("m=video 9 UDP/TLS/RTP/SAVPF 96"));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldAcceptPublishOfferWithInlineIceCandidate() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\""
                + escapeJson(publishOfferWithH264AndCandidate()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        JsonNode root = objectMapper.readTree(response.content().toString(CharsetUtil.UTF_8));
        assertEquals(0, root.get("code").asInt());
        assertEquals(1, sessionManager.count());
        assertNull(registry.findPublishedStream(new StreamKey(StreamProtocol.WEBRTC, "live", "cam01")));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldActivatePublishIngestOnlyAfterPeerConnectionConnected() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam03\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        WebRtcPublishPeerSession session = sessionManager.sessions().iterator().next();
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam03");
        assertNull(registry.findPublishedStream(streamKey));
        RTCRtpReceiver receiver = firstVideoReceiver(session.peerConnection());
        assertNotNull(receiver);

        receiver.getOnPacket().accept(videoPacket(0x01020304L, 3000L, 1));
        assertNull(registry.findPublishedStream(streamKey));

        emitConnectionState(session.peerConnection(), RTCPeerConnection.ConnectionState.CONNECTED);
        IPublishedStream stream = registry.findPublishedStream(streamKey);
        assertNotNull(stream);
        receiver.getOnPacket().accept(videoPacket(0x01020304L, 6000L, 2));

        assertEquals(0x01020304L, stream.latestTrackSsrc("video-0"));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldRejectDuplicateStreamPath() {
        StreamRegistry registry = new StreamRegistry();
        registry.registerPublishedStream(
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                new com.wenting.mediaserver.core.publish.DefaultPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam01"))
        );
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(409, response.status().code());
        assertEquals(0, sessionManager.count());
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldRejectDuplicatePendingPublishSessionForSamePath() {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel firstChannel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        firstChannel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam04\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));
        FullHttpResponse firstResponse = firstChannel.readOutbound();
        assertEquals(200, firstResponse.status().code());
        assertEquals(1, sessionManager.count());
        assertNull(registry.findPublishedStream(new StreamKey(StreamProtocol.WEBRTC, "live", "cam04")));

        EmbeddedChannel secondChannel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );
        secondChannel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam04\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));
        FullHttpResponse secondResponse = secondChannel.readOutbound();
        assertEquals(409, secondResponse.status().code());
        assertEquals(1, sessionManager.count());

        firstResponse.release();
        secondResponse.release();
        firstChannel.finishAndReleaseAll();
        secondChannel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldRejectPublishWithoutH264Video() {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(publishOfferWithVp8Only()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(400, response.status().code());
        assertEquals(0, sessionManager.count());
        assertNull(registry.findPublishedStream(new StreamKey(StreamProtocol.WEBRTC, "live", "cam01")));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldRemovePublishedStreamWhenPeerConnectionCloses() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcPublishHandler(
                        registry,
                        sessionManager,
                        new InetSocketAddress("192.168.3.52", 18081),
                        new NoopDatagramIoSender()
                ))
        );

        channel.writeInbound(request("{\"app\":\"live\",\"stream\":\"cam01\",\"sdp\":\"" + escapeJson(publishOfferWithH264()) + "\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        assertEquals(1, sessionManager.count());
        WebRtcPublishPeerSession session = sessionManager.sessions().iterator().next();
        session.peerConnection().close();

        assertEquals(0, sessionManager.count());
        assertNull(registry.findPublishedStream(new StreamKey(StreamProtocol.WEBRTC, "live", "cam01")));
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    private static DefaultFullHttpRequest request(String json) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/webrtc/publish",
                Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        request.headers().set("Content-Type", "application/json");
        request.headers().setInt("Content-Length", request.content().readableBytes());
        return request;
    }

    private static String publishOfferWithH264() {
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
                + "a=sendonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static String publishOfferWithH264AndCandidate() {
        return publishOfferWithH264()
                + "a=candidate:1 1 UDP 2130706431 127.0.0.1 50000 typ host\r\n";
    }

    private static String publishOfferWithVp8Only() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0\r\n"
                + "a=ice-ufrag:abcd\r\n"
                + "a=ice-pwd:abcdefghijklmnopqrstuv\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 97\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=setup:actpass\r\n"
                + "a=sendonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:97 VP8/90000\r\n";
    }

    private static String publishOfferWithH264AndOpus() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0 1\r\n"
                + "a=ice-ufrag:abcd\r\n"
                + "a=ice-pwd:abcdefghijklmnopqrstuv\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\n"
                + "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=setup:actpass\r\n"
                + "a=sendonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:111 opus/48000/2\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:1\r\n"
                + "a=setup:actpass\r\n"
                + "a=sendonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static RTCRtpReceiver firstVideoReceiver(RTCPeerConnection peerConnection) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (transceiver != null
                    && transceiver.getKind() == MediaStreamTrack.Kind.VIDEO
                    && transceiver.getReceiver() != null) {
                return transceiver.getReceiver();
            }
        }
        return null;
    }

    private static RtpPacket videoPacket(long ssrc, long timestamp, int sequenceNumber) {
        return new RtpPacket(
                2,
                false,
                false,
                0,
                true,
                96,
                sequenceNumber,
                timestamp,
                ssrc,
                null,
                new byte[]{0x65, 0x11, 0x22, 0x33}
        );
    }

    private static void emitConnectionState(RTCPeerConnection peerConnection,
                                            RTCPeerConnection.ConnectionState state) throws Exception {
        Method method = RTCPeerConnection.class.getDeclaredMethod("setConnectionState",
                RTCPeerConnection.ConnectionState.class);
        method.setAccessible(true);
        method.invoke(peerConnection, state);
    }

    private static final class NoopDatagramIoSender implements DatagramIoSender {
        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
