package com.wenting.mediaserver.protocol.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HttpRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final List<HttpRequestHandler> handlers;

    public HttpRouterHandler(HttpRequestHandler... handlers) {
        this.handlers = handlers == null
                ? Collections.<HttpRequestHandler>emptyList()
                : Collections.unmodifiableList(new ArrayList<HttpRequestHandler>(Arrays.asList(handlers)));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        for (HttpRequestHandler handler : handlers) {
            if (handler != null && handler.matches(req)) {
                handler.handleRequest(ctx, req);
                return;
            }
        }
        writeNotFound(ctx, req.uri());
    }

    private void writeNotFound(ChannelHandlerContext ctx, String uri) {
        byte[] content = ("no route for " + (uri == null ? "" : uri)).getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.NOT_FOUND,
                Unpooled.wrappedBuffer(content)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
