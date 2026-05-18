package com.wenting.mediaserver.protocol.http.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRouterHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCSessionDescription;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import io.netty.buffer.Unpooled;
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
import static org.junit.jupiter.api.Assertions.assertNull;

class WebRtcUnpublishHandlerTest {

    @Test
    void shouldRemoveSessionAndStreamOnExplicitUnpublish() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        SessionDatagramIo datagramIo = new SessionDatagramIo(
                new InetSocketAddress("192.168.3.52", 18081),
                new NoopDatagramIoSender()
        );
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        peerConnection.setRemoteDescription(new RTCSessionDescription("offer", publishOfferWithH264()));
        peerConnection.setLocalDescription(peerConnection.createAnswer().get());

        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        WebRtcPublishPeerSession session = new WebRtcPublishPeerSession(
                "publish-1",
                streamKey,
                peerConnection,
                datagramIo,
                registry
        );
        session.attachPublishedStream(stream);
        sessionManager.register(session);

        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcUnpublishHandler(sessionManager))
        );

        channel.writeInbound(request("{\"sessionId\":\"publish-1\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        assertEquals(0, sessionManager.count());
        assertNull(registry.findPublishedStream(streamKey));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturnNotFoundWhenSessionMissing() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcUnpublishHandler(new WebRtcPublishSessionManager()))
        );

        channel.writeInbound(request("{\"sessionId\":\"missing\"}"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(404, response.status().code());
        response.release();
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest request(String json) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/webrtc/unpublish",
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

    private static final class NoopDatagramIoSender implements DatagramIoSender {
        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
