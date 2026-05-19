package com.wenting.mediaserver.protocol.http.flv;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

public final class HttpFlvHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements HttpRequestHandler {

    private static final String FLV_PREFIX = "/flv/";

    private final StreamRegistry streamRegistry;

    public HttpFlvHandler(StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!matches(req)) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        handleRequest(ctx, req);
    }

    @Override
    public boolean matches(FullHttpRequest req) {
        return req != null && HttpMethod.GET.equals(req.method()) && parseFlvPath(req.uri()) != null;
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        HttpFlvPath flvPath = parseFlvPath(req.uri());
        IPublishedStream stream = streamRegistry.findPublishedStreamForHttpFlvPlayback(flvPath.app(), flvPath.stream());
        if (stream == null) {
            writeError(ctx, HttpResponseStatus.NOT_FOUND, "stream not found");
            return;
        }
        StreamKey streamKey = new StreamKey(stream.getProtocol(), flvPath.app(), flvPath.stream());
        HttpFlvSubscriberSession session = new HttpFlvSubscriberSession(ctx.channel().id().asShortText(), streamKey, ctx.channel());
        attachRtspTracksIfPresent(session, flvPath.app(), flvPath.stream());
        session.startResponse();
        stream.addSubscriber(new HttpFlvSubscriberAdapter(session));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String sessionId = ctx.channel().id().asShortText();
        for (IPublishedStream stream : streamRegistry.publishedStreamsSnapshot().values()) {
            if (stream != null) {
                stream.removeSubscriber(sessionId);
            }
        }
        super.channelInactive(ctx);
    }

    private void attachRtspTracksIfPresent(HttpFlvSubscriberSession subscriberSession, String app, String stream) {
        RtspSessionManager sessionManager = streamRegistry.getRtspSessionManager();
        if (sessionManager == null) {
            return;
        }
        RtspSession publisherSession = findRtspPublisherSession(sessionManager, app, stream);
        if (publisherSession == null || publisherSession.trackList() == null) {
            return;
        }
        for (ITrack track : publisherSession.trackList()) {
            subscriberSession.track(track);
        }
    }

    private RtspSession findRtspPublisherSession(RtspSessionManager sessionManager, String app, String stream) {
        if (sessionManager == null) {
            return null;
        }
        for (RtspSession session : sessionManager.sessions()) {
            if (session == null || !session.isPublisher() || session.streamKey() == null) {
                continue;
            }
            if (app.equals(session.streamKey().app()) && stream.equals(session.streamKey().stream())) {
                return session;
            }
        }
        return null;
    }

    private HttpFlvPath parseFlvPath(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }
        String path = uri;
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        if (!path.endsWith(".flv")) {
            return null;
        }
        if (!path.startsWith(FLV_PREFIX)) {
            return null;
        }
        String normalized = path.substring(FLV_PREFIX.length());
        int firstSlash = normalized.indexOf('/');
        if (firstSlash <= 0 || firstSlash == normalized.length() - 1) {
            return null;
        }
        String app = normalized.substring(0, firstSlash);
        String stream = normalized.substring(firstSlash + 1, normalized.length() - 4);
        if (app.trim().isEmpty() || stream.trim().isEmpty()) {
            return null;
        }
        return new HttpFlvPath(app, stream);
    }

    private void writeError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] content = message == null ? new byte[0] : message.getBytes(CharsetUtil.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, io.netty.buffer.Unpooled.wrappedBuffer(content));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

}
