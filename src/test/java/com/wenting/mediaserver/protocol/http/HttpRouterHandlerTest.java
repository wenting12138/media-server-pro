package com.wenting.mediaserver.protocol.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouterHandlerTest {

    @Test
    void shouldDispatchToMatchedHandlerByPrefix() {
        RecordingHandler hls = new RecordingHandler("/hls/");
        RecordingHandler flv = new RecordingHandler("/flv/");
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRouterHandler(hls, flv));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/test/index.m3u8"));

        assertEquals(1, hls.callCount);
        assertEquals(0, flv.callCount);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldDispatchToSecondMatchedHandlerByPrefix() {
        RecordingHandler hls = new RecordingHandler("/hls/");
        RecordingHandler flv = new RecordingHandler("/flv/");
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRouterHandler(hls, flv));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/flv/live/test.flv"));

        assertEquals(0, hls.callCount);
        assertEquals(1, flv.callCount);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldDispatchToExactWebRtcTestPageRoute() {
        RecordingHandler testPage = new RecordingHandler("/webrtc/test");
        RecordingHandler play = new RecordingHandler("/webrtc/play");
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRouterHandler(testPage, play));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/webrtc/test"));

        assertEquals(1, testPage.callCount);
        assertEquals(0, play.callCount);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturn404WhenNoRouteMatches() {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRouterHandler(new RecordingHandler("/hls/")));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/unknown/path"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        assertTrue(response.content().toString(io.netty.util.CharsetUtil.UTF_8).contains("no route"));
        response.release();
        channel.finishAndReleaseAll();
    }

    private static final class RecordingHandler implements HttpRequestHandler {
        private final String prefix;
        private int callCount;

        private RecordingHandler(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean matches(FullHttpRequest request) {
            return request != null && request.uri() != null && request.uri().startsWith(prefix);
        }

        @Override
        public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            callCount++;
        }
    }
}
