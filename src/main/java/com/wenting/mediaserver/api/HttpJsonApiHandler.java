package com.wenting.mediaserver.api;

import com.wenting.mediaserver.config.MediaServerConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntSupplier;

/**
 * Minimal admin HTTP API. Paths mirror a subset of ZLM's {@code /index/api/*} style for familiarity.
 */
public final class HttpJsonApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonApiHandler.class);

    private final MediaServerConfig config;

    public HttpJsonApiHandler(MediaServerConfig config) {
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {

    }

    private String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
