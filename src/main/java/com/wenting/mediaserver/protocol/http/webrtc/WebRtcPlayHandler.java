package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.webrtc.ServerWebRtcPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCSessionDescription;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
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

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Minimal server-side WebRTC play signaling endpoint.
 */
public final class WebRtcPlayHandler implements HttpRequestHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PLAY_PATH = "/webrtc/play";

    private final StreamRegistry registry;
    private final WebRtcSessionManager sessionManager;
    private final InetSocketAddress localUdpAddress;
    private final DatagramIoSender datagramSender;

    public WebRtcPlayHandler(
            StreamRegistry registry,
            WebRtcSessionManager sessionManager,
            InetSocketAddress localUdpAddress,
            DatagramIoSender datagramSender
    ) {
        this.registry = registry;
        this.sessionManager = sessionManager;
        this.localUdpAddress = localUdpAddress;
        this.datagramSender = datagramSender;
    }

    @Override
    public boolean matches(FullHttpRequest request) {
        return request != null
                && HttpMethod.POST.equals(request.method())
                && PLAY_PATH.equals(extractPath(request.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(request.content().toString(CharsetUtil.UTF_8));
        String app = text(root, "app");
        String streamName = text(root, "stream");
        String offerSdp = text(root, "sdp");
        if (isBlank(app) || isBlank(streamName) || isBlank(offerSdp)) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"missing app/stream/sdp\"}");
            return;
        }
        IPublishedStream stream = registry.findPublishedStreamByPath(app, streamName);
        if (stream == null) {
            writeJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"code\":-1,\"msg\":\"stream not found\"}");
            return;
        }

        SessionDatagramIo datagramIo = new SessionDatagramIo(localUdpAddress, datagramSender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        boolean success = false;
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerSdp);
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);

            ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                    UUID.randomUUID().toString(),
                    new StreamKey(stream.getProtocol(), app, streamName),
                    peerConnection,
                    datagramIo
            );
            sessionManager.register(session);

            String body = OBJECT_MAPPER.writeValueAsString(new PlayResponse(0, "success", new PlayResponseData(answer.getType(), answer.getSdp())));
            writeJson(ctx, HttpResponseStatus.OK, body);
            success = true;
        } finally {
            if (!success) {
                peerConnection.close();
            }
        }
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

    private static final class PlayResponse {
        public final int code;
        public final String msg;
        public final PlayResponseData data;

        private PlayResponse(int code, String msg, PlayResponseData data) {
            this.code = code;
            this.msg = msg;
            this.data = data;
        }
    }

    private static final class PlayResponseData {
        public final String type;
        public final String sdp;

        private PlayResponseData(String type, String sdp) {
            this.type = type;
            this.sdp = sdp;
        }
    }
}
