package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackSessionManager;
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
 * Minimal server-side WebRTC play signaling endpoint.
 */
public final class WebRtcPlayHandler implements HttpRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(WebRtcPlayHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PLAY_PATH = "/webrtc/play";

    private final StreamRegistry registry;
    private final WebRtcPlaybackSessionManager sessionManager;
    private final InetSocketAddress localUdpAddress;
    private final DatagramIoSender datagramSender;

    public WebRtcPlayHandler(
            StreamRegistry registry,
            WebRtcPlaybackSessionManager sessionManager,
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
        IPublishedStream stream = registry.findPublishedStreamForWebRtcPlayback(app, streamName);
        if (stream == null) {
            writeJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"code\":-1,\"msg\":\"stream not found\"}");
            return;
        }

        SessionDatagramIo datagramIo = new SessionDatagramIo(localUdpAddress, datagramSender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPlaybackPeerSession session = null;
        boolean success = false;
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerSdp);
            peerConnection.setRemoteDescription(offer);
            configureOutgoingTracks(peerConnection, streamName);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);
            log.info("coming  offer: \r\n{}",  offer.getSdp());
            log.info("create answer: \r\n{}", answer.getSdp());

            session = new WebRtcPlaybackPeerSession(
                    UUID.randomUUID().toString(),
                    new StreamKey(stream.getProtocol(), app, streamName),
                    peerConnection,
                    datagramIo
            );
            installLifecycleCleanup(session);
            session.attachPublishedStream(stream);
            requestInitialVideoKeyFrame(stream);
            sessionManager.register(session);

            String body = OBJECT_MAPPER.writeValueAsString(new PlayResponse(
                    0,
                    "success",
                    new PlayResponseData(session.sessionId(), answer.getType(), answer.getSdp())
            ));
            writeJson(ctx, HttpResponseStatus.OK, body);
            success = true;
        } finally {
            if (!success) {
                if (session != null) {
                    closeManagedSession(session);
                } else {
                    peerConnection.close();
                    datagramIo.close();
                }
            }
        }
    }

    private void installLifecycleCleanup(WebRtcPlaybackPeerSession session) {
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

    private void closeManagedSession(WebRtcPlaybackPeerSession session) {
        WebRtcPlaybackPeerSession removed = sessionManager.removeAndClose(session.sessionId());
        if (removed == null) {
            session.close();
        }
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
            log.warn("WebRTC connection-state callback failed: {}", e.getMessage(), e);
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
            log.warn("WebRTC ICE-state callback failed: {}", e.getMessage(), e);
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

    private static void configureOutgoingTracks(RTCPeerConnection peerConnection, String streamName) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (transceiver == null
                    || transceiver.getSender() == null
                    || transceiver.getSender().getTrack() != null
                    || !canSend(transceiver)) {
                continue;
            }
            String trackIdPrefix = transceiver.getKind() == MediaStreamTrack.Kind.AUDIO
                    ? "webrtc-audio-"
                    : "webrtc-video-";
            transceiver.getSender().replaceTrack(new MediaStreamTrack(
                    transceiver.getKind(),
                    trackIdPrefix + safeTrackId(streamName)
            ));
        }
    }

    private static boolean canSend(RTCRtpTransceiver transceiver) {
        return transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDONLY
                || transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDRECV;
    }

    private static String safeTrackId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "stream";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    private void requestInitialVideoKeyFrame(IPublishedStream stream) {
        if (stream == null) {
            return;
        }
        String trackId = stream.firstVideoTrackId();
        if (trackId == null || trackId.trim().isEmpty()) {
            return;
        }
        boolean accepted = stream.requestKeyFrame(trackId);
        log.debug("Requested initial WebRTC playback keyframe stream={} track={} accepted={}",
                stream, trackId, accepted);
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
        public final String sessionId;
        public final String type;
        public final String sdp;

        private PlayResponseData(String sessionId, String type, String sdp) {
            this.sessionId = sessionId;
            this.type = type;
            this.sdp = sdp;
        }
    }
}
