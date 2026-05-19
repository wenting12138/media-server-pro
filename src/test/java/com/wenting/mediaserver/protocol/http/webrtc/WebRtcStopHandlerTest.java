package com.wenting.mediaserver.protocol.http.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.protocol.http.HttpRouterHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
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

class WebRtcStopHandlerTest {

    @Test
    void shouldCloseSessionAndRemoveSubscriberWhenStopCalled() throws Exception {
        WebRtcPlaybackSessionManager sessionManager = new WebRtcPlaybackSessionManager();
        DefaultPublishedStream publishedStream = new DefaultPublishedStream(
                new StreamKey(StreamProtocol.RTMP, "live", "cam01")
        );
        SessionDatagramIo datagramIo = new SessionDatagramIo(
                new InetSocketAddress("127.0.0.1", 18081),
                new NoopDatagramIoSender()
        );
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
                "sess-stop-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                peerConnection,
                datagramIo
        );
        session.attachPublishedStream(publishedStream);
        sessionManager.register(session);

        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcStopHandler(sessionManager))
        );
        try {
            channel.writeInbound(request("{\"sessionId\":\"sess-stop-1\"}"));

            FullHttpResponse response = channel.readOutbound();
            assertEquals(200, response.status().code());
            assertTrue(response.content().toString(CharsetUtil.UTF_8).contains("\"code\":0"));
            assertEquals(0, sessionManager.count());
            assertEquals(0, publishedStream.subscriberCount());
            response.release();
        } finally {
            channel.finishAndReleaseAll();
            sessionManager.close();
        }
    }

    @Test
    void shouldReturnNotFoundForUnknownSession() {
        WebRtcPlaybackSessionManager sessionManager = new WebRtcPlaybackSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpRouterHandler(new WebRtcStopHandler(sessionManager))
        );
        try {
            channel.writeInbound(request("{\"sessionId\":\"missing\"}"));

            FullHttpResponse response = channel.readOutbound();
            assertEquals(404, response.status().code());
            assertTrue(response.content().toString(CharsetUtil.UTF_8).contains("session not found"));
            response.release();
        } finally {
            channel.finishAndReleaseAll();
            sessionManager.close();
        }
    }

    private static DefaultFullHttpRequest request(String json) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/webrtc/stop",
                io.netty.buffer.Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        request.headers().set("Content-Type", "application/json");
        request.headers().setInt("Content-Length", request.content().readableBytes());
        return request;
    }

    private static final class NoopDatagramIoSender implements DatagramIoSender {
        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
