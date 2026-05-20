package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.http.utils.HttpUtil;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPlaybackSessionManager;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCSessionDescription;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Minimal server-side WebRTC play signaling endpoint.
 */
public final class WebRtcPlayHandler extends AbstractWebRtcSessionHandler<WebRtcPlaybackPeerSession> {

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
                && PLAY_PATH.equals(HttpUtil.extractPath(request.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(request.content().toString(CharsetUtil.UTF_8));
        String app = HttpUtil.text(root, "app");
        String streamName = HttpUtil.text(root, "stream");
        String offerSdp = HttpUtil.text(root, "sdp");
        if (HttpUtil.isBlank(app) || HttpUtil.isBlank(streamName) || HttpUtil.isBlank(offerSdp)) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"missing app/stream/sdp\"}");
            return;
        }
        IPublishedStream stream = registry.findPublishedStreamForWebRtcPlayback(app, streamName);
        if (stream == null) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"code\":-1,\"msg\":\"stream not found\"}");
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
//            log.info("coming  offer: \r\n{}",  offer.getSdp());
//            log.info("create answer: \r\n{}", answer.getSdp());

            session = new WebRtcPlaybackPeerSession(
                    UUID.randomUUID().toString(),
                    new StreamKey(stream.getProtocol(), app, streamName),
                    peerConnection,
                    datagramIo
            );
            String body = OBJECT_MAPPER.writeValueAsString(new PlayResponse(
                    0,
                    "success",
                    new PlayResponseData(session.sessionId(), answer.getType(), answer.getSdp())
            ));
            HttpUtil.writeJson(ctx, HttpResponseStatus.OK, body);
            WebRtcPlaybackPeerSession managedSession = session;
            managedSession.installLifecycleCleanup(() -> closeManagedSession(managedSession));
            session.attachPublishedStream(stream);
            sessionManager.register(session);
            success = true;
        } finally {
            cleanupFailedRequest(success, session, peerConnection, datagramIo, null);
        }
    }

    @Override
    protected WebRtcPlaybackPeerSession removeAndCloseManagedSession(String sessionId) {
        return sessionManager.removeAndClose(sessionId);
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
