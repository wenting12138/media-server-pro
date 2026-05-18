package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishSessionManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * Explicit server-side WebRTC unpublish endpoint for frontend-triggered teardown.
 */
public final class WebRtcUnpublishHandler implements HttpRequestHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNPUBLISH_PATH = "/webrtc/unpublish";

    private final WebRtcPublishSessionManager sessionManager;

    public WebRtcUnpublishHandler(WebRtcPublishSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean matches(FullHttpRequest request) {
        return request != null
                && HttpMethod.POST.equals(request.method())
                && UNPUBLISH_PATH.equals(extractPath(request.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(request.content().toString(CharsetUtil.UTF_8));
        String sessionId = text(root, "sessionId");
        if (isBlank(sessionId)) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"missing sessionId\"}");
            return;
        }
        WebRtcPublishPeerSession removed = sessionManager == null ? null : sessionManager.removeAndClose(sessionId);
        if (removed == null) {
            writeJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"code\":-1,\"msg\":\"session not found\"}");
            return;
        }
        writeJson(ctx, HttpResponseStatus.OK, "{\"code\":0,\"msg\":\"success\"}");
    }

    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        byte[] content = body.getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(content)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }
}
