package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.http.utils.HttpUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HlsHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HlsHandler.class);
    private static final String HLS_PREFIX = "/hls/";

    private final StreamRegistry streamRegistry;
    private final HlsSessionManager hlsSessionManager;

    public HlsHandler(StreamRegistry streamRegistry, HlsSessionManager hlsSessionManager) {
        this.streamRegistry = streamRegistry;
        this.hlsSessionManager = hlsSessionManager;
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
        return req != null && HttpMethod.GET.equals(req.method()) && parse(req.uri()) != null;
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        HlsPath hlsPath = parse(req.uri());
        IPublishedStream stream = streamRegistry.findPublishedStreamForHlsPlayback(hlsPath.app(), hlsPath.stream());
        if (stream == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "stream not found");
            return;
        }
        StreamKey streamKey = new StreamKey(stream.getProtocol(), hlsPath.app(), hlsPath.stream());
        HlsSession session = hlsSessionManager.ensureSession(streamKey, stream);
        if (hlsPath.playlist()) {
            byte[] content = session.playlist().getBytes(CharsetUtil.UTF_8);
            HttpUtil.writeBytes(ctx, HttpResponseStatus.OK, content, "application/vnd.apple.mpegurl",
                    response -> {
                        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
                        response.headers().set("Access-Control-Allow-Origin", "*");
                    },
                    false);
            return;
        }
        byte[] content = session.segmentBytes(parseSequence(hlsPath.fileName()));
        if (content == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "hls segment not ready");
            return;
        }
        HttpUtil.writeBytes(ctx, HttpResponseStatus.OK, content, "video/mp2t",
                response -> {
                    response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=1");
                    response.headers().set("Access-Control-Allow-Origin", "*");
                },
                false);
    }

    private HlsPath parse(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }
        String path = HttpUtil.extractPath(uri);
        if (!path.startsWith(HLS_PREFIX)) {
            return null;
        }
        String normalized = path.substring(HLS_PREFIX.length());
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
        HttpUtil.writeBytes(ctx, status, content, "text/plain; charset=UTF-8", true);
    }
}
