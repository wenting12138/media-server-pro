package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HlsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HlsHandler.class);

    private final StreamRegistry streamRegistry;
    private final HlsSessionManager hlsSessionManager;

    public HlsHandler(StreamRegistry streamRegistry, HlsSessionManager hlsSessionManager) {
        this.streamRegistry = streamRegistry;
        this.hlsSessionManager = hlsSessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (!HttpMethod.GET.equals(req.method())) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        HlsPath hlsPath = parse(req.uri());
        if (hlsPath == null) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        IPublishedStream stream = streamRegistry.findPublishedStreamByPath(hlsPath.app(), hlsPath.stream());
        if (stream == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "stream not found");
            return;
        }
        StreamKey streamKey = new StreamKey(stream.getProtocol(), hlsPath.app(), hlsPath.stream());
        HlsSession session = hlsSessionManager.ensureSession(streamKey, stream);
        if (hlsPath.playlist()) {
            byte[] content = session.playlist().getBytes(CharsetUtil.UTF_8);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(content));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/vnd.apple.mpegurl");
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
            response.headers().set("Access-Control-Allow-Origin", "*");
            ctx.writeAndFlush(response);
            return;
        }
        byte[] content = session.segmentBytes(parseSequence(hlsPath.fileName()));
        if (content == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "hls segment not ready");
            return;
        }
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "video/mp2t");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=1");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.headers().set("Access-Control-Allow-Origin", "*");
        ctx.writeAndFlush(response);
    }

    private HlsPath parse(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }
        String path = uri;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = normalized.split("/");
        if (parts.length != 3) {
            return null;
        }
        HlsPath hlsPath = new HlsPath(parts[0], parts[1], parts[2]);
        if (hlsPath.app().trim().isEmpty() || hlsPath.stream().trim().isEmpty()) {
            return null;
        }
        return hlsPath.playlist() || hlsPath.segment() ? hlsPath : null;
    }

    private long parseSequence(String fileName) {
        if (fileName == null || !fileName.startsWith("seg-") || !fileName.endsWith(".ts")) {
            return -1L;
        }
        try {
            return Long.parseLong(fileName.substring(4, fileName.length() - 3));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] content = message == null ? new byte[0] : message.getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
