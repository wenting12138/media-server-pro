package com.wenting.mediaserver.protocol.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public interface HttpRequestHandler {

    boolean matches(FullHttpRequest request);

    void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception;
}
