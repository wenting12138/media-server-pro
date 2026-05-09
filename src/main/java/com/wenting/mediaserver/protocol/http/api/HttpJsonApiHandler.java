package com.wenting.mediaserver.protocol.http.api;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal admin HTTP API. Paths mirror a subset of ZLM's {@code /index/api/*} style for familiarity.
 */
public final class HttpJsonApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonApiHandler.class);

    private final MediaServerConfig config;

    public HttpJsonApiHandler(MediaServerConfig config) {
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!matches(req)) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        handleRequest(ctx, req);
    }

    @Override
    public boolean matches(FullHttpRequest req) {
        return req != null
                && HttpMethod.GET.equals(req.method())
                && extractPath(req.uri()).startsWith("/index/api/");
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        byte[] content = "{\"code\":-1,\"msg\":\"not implemented\"}".getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_IMPLEMENTED,
                Unpooled.wrappedBuffer(content)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.writeAndFlush(response);
    }

    private String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
