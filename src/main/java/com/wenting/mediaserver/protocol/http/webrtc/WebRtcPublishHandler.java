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
import com.wenting.mediaserver.protocol.http.utils.HttpUtil;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPublishSessionManager;
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
 * Minimal server-side WebRTC publish signaling endpoint.
 */
public final class WebRtcPublishHandler extends AbstractWebRtcSessionHandler<WebRtcPublishPeerSession> {

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
                && PUBLISH_PATH.equals(HttpUtil.extractPath(request.uri()));
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
        if (registry.findPublishedStreamByPath(app, streamName) != null) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.CONFLICT, "{\"code\":-1,\"msg\":\"stream already exists\"}");
            return;
        }
        if (sessionManager.findByStreamKey(new StreamKey(StreamProtocol.WEBRTC, app, streamName)) != null) {
            HttpUtil.writeJson(ctx, HttpResponseStatus.CONFLICT, "{\"code\":-1,\"msg\":\"stream publish already pending\"}");
            return;
        }

        SessionDatagramIo datagramIo = new SessionDatagramIo(localUdpAddress, datagramSender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPublishPeerSession session = null;
        boolean success = false;
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, app, streamName);
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerSdp);
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            if (!hasSupportedIncomingH264(peerConnection)) {
                HttpUtil.writeJson(ctx, HttpResponseStatus.BAD_REQUEST, "{\"code\":-1,\"msg\":\"H264 video publish is required\"}");
                return;
            }
            peerConnection.setLocalDescription(answer);
//            log.info("incoming publish offer: \r\n{}", offer.getSdp());
//            log.info("create publish answer: \r\n{}", answer.getSdp());
            IPublishedStream stream = new DefaultPublishedStream(streamKey);
            session = new WebRtcPublishPeerSession(
                    UUID.randomUUID().toString(),
                    streamKey,
                    peerConnection,
                    datagramIo,
                    registry
            );
            String body = OBJECT_MAPPER.writeValueAsString(new PublishResponse(
                    0,
                    "success",
                    new PublishResponseData(session.sessionId(), answer.getType(), answer.getSdp())
            ));
            HttpUtil.writeJson(ctx, HttpResponseStatus.OK, body);
            WebRtcPublishPeerSession managedSession = session;
            session.attachPublishedStream(stream);
            sessionManager.register(session);
            managedSession.installLifecycleCleanup(() -> closeManagedSession(managedSession));
            success = true;
        } finally {
            cleanupFailedRequest(success, session, peerConnection, datagramIo,
                    () -> registry.removePublishedStream(streamKey));
        }
    }

    @Override
    protected WebRtcPublishPeerSession removeAndCloseManagedSession(String sessionId) {
        return sessionManager.removeAndClose(sessionId);
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
