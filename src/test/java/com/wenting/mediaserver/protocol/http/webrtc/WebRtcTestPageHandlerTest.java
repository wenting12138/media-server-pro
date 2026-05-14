package com.wenting.mediaserver.protocol.http.webrtc;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcTestPageHandlerTest {

    @Test
    void shouldServeStaticWebRtcTestPage() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebRtcTestPageHandler());

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/webrtc/test"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        assertEquals("text/html; charset=UTF-8", response.headers().get("Content-Type"));
        String body = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("/webrtc/play"));
        assertTrue(body.contains("/webrtc/stop"));
        assertTrue(body.contains("sessionId"));
        assertTrue(body.contains("RTCPeerConnection"));
        response.release();
        channel.finishAndReleaseAll();
    }
}
