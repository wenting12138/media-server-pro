package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Minimal server-side WebRTC publish signaling endpoint.
 */
public final class WebRtcPublishHandler implements HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(WebRtcPublishHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PUBLISH_PATH = "/webrtc/publish";

    private final StreamRegistry registry;
    private final WebRtcPublishSessionManager sessionManager;
    private final InetSocketAddress localUdpAddress;
    private final DatagramIoSender datagramSender;

    public WebRtcPublishHandler(
            StreamRegistry registry,
            WebRtcPublishSessionManager sessionManager,
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
                && PUBLISH_PATH.equals(extractPath(request.uri()));
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
        if (registry.findPublishedStreamByPath(app, streamName) != null) {
            writeJson(ctx, HttpResponseStatus.CONFLICT, "{\"code\":-1,\"msg\":\"stream already exists\"}");
            return;
        }

        SessionDatagramIo datagramIo = new SessionDatagramIo(localUdpAddress, datagramSender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPublishPeerSession session = null;
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, app, streamName);
        boolean success = false;
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerSdp);
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            if (!hasSupportedIncomingH264(peerConnection)) {
                writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"only H264 video publish is supported\"}");
                return;
            }
            peerConnection.setLocalDescription(answer);
            log.info("incoming publish offer: \r\n{}", offer.getSdp());
            log.info("create publish answer: \r\n{}", answer.getSdp());

            IPublishedStream stream = new DefaultPublishedStream(streamKey);
            registry.registerPublishedStream(streamKey, stream);
            session = new WebRtcPublishPeerSession(
                    UUID.randomUUID().toString(),
                    streamKey,
                    peerConnection,
                    datagramIo,
                    registry
            );
            installLifecycleCleanup(session);
            session.attachPublishedStream(stream);
            sessionManager.register(session);

            String body = OBJECT_MAPPER.writeValueAsString(new PublishResponse(
                    0,
                    "success",
                    new PublishResponseData(session.sessionId(), answer.getType(), answer.getSdp())
            ));
            writeJson(ctx, HttpResponseStatus.OK, body);
            success = true;
        } finally {
            if (!success) {
                if (session != null) {
                    closeManagedSession(session);
                } else {
                    registry.removePublishedStream(streamKey);
                    peerConnection.close();
                    datagramIo.close();
                }
            }
        }
    }

    private void installLifecycleCleanup(WebRtcPublishPeerSession session) {
        RTCPeerConnection peerConnection = session.peerConnection();
        Consumer<RTCPeerConnection.ConnectionState> previousConnectionHandler = peerConnection.onConnectionStateChange;
        peerConnection.onConnectionStateChange = state -> {
            invokeConnectionHandler(previousConnectionHandler, state);
            if (state == RTCPeerConnection.ConnectionState.FAILED
                    || state == RTCPeerConnection.ConnectionState.CLOSED) {
                closeManagedSession(session);
            }
        };
        Consumer<RTCPeerConnection.IceConnectionState> previousIceHandler = peerConnection.onIceConnectionStateChange;
        peerConnection.onIceConnectionStateChange = state -> {
            invokeIceHandler(previousIceHandler, state);
            if (state == RTCPeerConnection.IceConnectionState.FAILED
                    || state == RTCPeerConnection.IceConnectionState.CLOSED) {
                closeManagedSession(session);
            }
        };
    }

    private void closeManagedSession(WebRtcPublishPeerSession session) {
        WebRtcPublishPeerSession removed = sessionManager.removeAndClose(session.sessionId());
        if (removed == null) {
            session.close();
        }
    }

    private boolean hasSupportedIncomingH264(RTCPeerConnection peerConnection) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (transceiver == null || transceiver.getReceiver() == null) {
                continue;
            }
            if (transceiver.getKind() != MediaStreamTrack.Kind.VIDEO) {
                continue;
            }
            if (transceiver.getDirection() != RTCRtpTransceiver.Direction.RECVONLY
                    && transceiver.getDirection() != RTCRtpTransceiver.Direction.SENDRECV) {
                continue;
            }
            if (transceiver.getNegotiatedCodecType() == CodecType.H264) {
                return true;
            }
        }
        return false;
    }

    private void invokeConnectionHandler(
            Consumer<RTCPeerConnection.ConnectionState> handler,
            RTCPeerConnection.ConnectionState state
    ) {
        if (handler == null) {
            return;
        }
        try {
            handler.accept(state);
        } catch (RuntimeException e) {
            log.warn("WebRTC publish connection-state callback failed: {}", e.getMessage(), e);
        }
    }

    private void invokeIceHandler(
            Consumer<RTCPeerConnection.IceConnectionState> handler,
            RTCPeerConnection.IceConnectionState state
    ) {
        if (handler == null) {
            return;
        }
        try {
            handler.accept(state);
        } catch (RuntimeException e) {
            log.warn("WebRTC publish ICE-state callback failed: {}", e.getMessage(), e);
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

    private static final class PublishResponse {
        public final int code;
        public final String msg;
        public final PublishResponseData data;

        private PublishResponse(int code, String msg, PublishResponseData data) {
            this.code = code;
            this.msg = msg;
            this.data = data;
        }
    }

    private static final class PublishResponseData {
        public final String sessionId;
        public final String type;
        public final String sdp;

        private PublishResponseData(String sessionId, String type, String sdp) {
            this.sessionId = sessionId;
            this.type = type;
            this.sdp = sdp;
        }
    }
}
