package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.http.utils.HttpUtil;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

/**
 * Explicit server-side WebRTC stop endpoint for frontend-triggered teardown.
 */
public final class WebRtcStopHandler implements HttpRequestHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STOP_PATH = "/webrtc/stop";

    private final WebRtcPlaybackSessionManager sessionManager;

    public WebRtcStopHandler(WebRtcPlaybackSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean matches(FullHttpRequest request) {
        return request != null
                && HttpMethod.POST.equals(request.method())
                && STOP_PATH.equals(HttpUtil.extractPath(request.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(request.content().toString(CharsetUtil.UTF_8));
        String sessionId = HttpUtil.text(root, "sessionId");
        if (HttpUtil.isBlank(sessionId)) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"missing sessionId\"}");
            return;
        }
        WebRtcPlaybackPeerSession removed = sessionManager == null ? null : sessionManager.removeAndClose(sessionId);
        if (removed == null) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"code\":-1,\"msg\":\"session not found\"}");
            return;
        }
        HttpUtil.writeJson(ctx, HttpResponseStatus.OK, "{\"code\":0,\"msg\":\"success\"}");
    }
}
