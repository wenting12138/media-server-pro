package com.wenting.mediaserver.protocol.http.webrtc;

import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.http.utils.HttpUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.ByteArrayOutputStream;

public final class WebRtcTestPageHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements HttpRequestHandler {

    private static final String WEBRTC_TEST_PATH = "/webrtc/test";
    private static final String WEBRTC_TEST_PAGE_RESOURCE = "http/webrtc/test.html";
    private static final byte[] WEBRTC_TEST_PAGE_BYTES = loadPageBytes();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!matches(req)) {
            ctx.fireChannelRead(req.retain());
                return;
        }
        handleRequest(ctx, req);
    }

    @Override
    public boolean matches(FullHttpRequest request) {
        return request != null
                && HttpMethod.GET.equals(request.method())
                && WEBRTC_TEST_PATH.equals(HttpUtil.extractPath(request.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        HttpUtil.writeBytes(ctx, HttpResponseStatus.OK, WEBRTC_TEST_PAGE_BYTES,
                "text/html; charset=UTF-8",
                response -> response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache"),
                false);
    }

    private static byte[] loadPageBytes() {
        try (InputStream inputStream = WebRtcTestPageHandler.class.getClassLoader()
                .getResourceAsStream(WEBRTC_TEST_PAGE_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing resource: " + WEBRTC_TEST_PAGE_RESOURCE);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load resource: " + WEBRTC_TEST_PAGE_RESOURCE, e);
        }
    }

}
