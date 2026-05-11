package com.wenting.mediaserver.protocol.http.webrtc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.http.api.ApiResponse;
import com.wenting.mediaserver.protocol.webrtc.WebRtcOfferParser;
import com.wenting.mediaserver.protocol.webrtc.WebRtcOfferSummary;
import com.wenting.mediaserver.protocol.webrtc.WebRtcPeerSession;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSdpAnswerBuilder;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsServerTransport;
import com.wenting.mediaserver.protocol.webrtc.dtls.WebRtcCertificateManager;
import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;
import io.netty.buffer.Unpooled;
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

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WebRtcPlayHandler extends SimpleChannelInboundHandler<FullHttpRequest> implements HttpRequestHandler {

    private static final String WEBRTC_PLAY_PATH = "/webrtc/play";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(WebRtcPlayHandler.class);

    private final StreamRegistry streamRegistry;
    private final WebRtcSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebRtcOfferParser offerParser = new WebRtcOfferParser();
    private final WebRtcSdpAnswerBuilder answerBuilder = new WebRtcSdpAnswerBuilder();
    private final int candidatePort;
    private final WebRtcCertificateManager certificateManager;

    public WebRtcPlayHandler(StreamRegistry streamRegistry, WebRtcSessionManager sessionManager, int candidatePort) {
        this(streamRegistry, sessionManager, candidatePort, new WebRtcCertificateManager());
    }

    public WebRtcPlayHandler(
            StreamRegistry streamRegistry,
            WebRtcSessionManager sessionManager,
            int candidatePort,
            WebRtcCertificateManager certificateManager
    ) {
        this.streamRegistry = streamRegistry;
        this.sessionManager = sessionManager;
        this.candidatePort = candidatePort;
        this.certificateManager = certificateManager == null ? new WebRtcCertificateManager() : certificateManager;
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
        return req != null && HttpMethod.POST.equals(req.method()) && WEBRTC_PLAY_PATH.equals(extractPath(req.uri()));
    }

    @Override
    public void handleRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        WebRtcPlayRequest playRequest;
        try {
            playRequest = objectMapper.readValue(req.content().toString(CharsetUtil.UTF_8), WebRtcPlayRequest.class);
        } catch (Exception e) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(-1, "invalid webrtc play request"));
            return;
        }
        if (playRequest == null
                || isBlank(playRequest.getApp())
                || isBlank(playRequest.getStream())
                || isBlank(playRequest.getSdp())) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(-1, "app, stream and sdp are required"));
            return;
        }
        IPublishedStream stream = streamRegistry.findPublishedStreamByPath(playRequest.getApp(), playRequest.getStream());
        if (stream == null) {
            writeJson(ctx, HttpResponseStatus.NOT_FOUND, ApiResponse.error(-1, "stream not found"));
            return;
        }
        WebRtcOfferSummary offerSummary = offerParser.parse(playRequest.getSdp());
        if (!offerSummary.supportsH264Video()) {
            writeJson(ctx, HttpResponseStatus.BAD_REQUEST, ApiResponse.error(-1, "offer must advertise H264 video"));
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        String iceUfrag = randomToken(8);
        String icePwd = randomToken(24);
        String fingerprint = certificateManager.certificate().fingerprintSha256();
        StreamProtocol sourceProtocol = stream.getProtocol() == null ? StreamProtocol.UNKNOWN : stream.getProtocol();
        StreamKey targetStreamKey = new StreamKey(sourceProtocol, playRequest.getApp().trim(), playRequest.getStream().trim());
        String candidateIp = resolveCandidateIp(ctx);
        int selectedCandidatePort = resolveCandidatePort(ctx);
        IceAgent iceAgent = new IceAgent(iceUfrag, icePwd);
        iceAgent.addHostCandidate(candidateIp, selectedCandidatePort);
        WebRtcPeerSession provisional = new WebRtcPeerSession(
                sessionId,
                targetStreamKey,
                playRequest.getSdp(),
                null,
                iceUfrag,
                icePwd,
                fingerprint,
                iceAgent,
                System.currentTimeMillis()
        );
        String answerSdp = answerBuilder.build(provisional, offerSummary, candidateIp, selectedCandidatePort);
        WebRtcPeerSession session = new WebRtcPeerSession(
                sessionId,
                targetStreamKey,
                playRequest.getSdp(),
                answerSdp,
                iceUfrag,
                icePwd,
                fingerprint,
                iceAgent,
                provisional.createdAtMillis()
        );
        session.dtlsServerTransport(new DtlsServerTransport(sessionId, certificateManager.certificate()));
        sessionManager.register(session);
        log.info("webrtc offSdp: {}, answerSdp: {}", playRequest.getSdp(), answerSdp);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sessionId", session.sessionId());
        data.put("app", playRequest.getApp().trim());
        data.put("stream", playRequest.getStream().trim());
        data.put("type", "answer");
        data.put("sdp", answerSdp);
        writeJson(ctx, HttpResponseStatus.OK, ApiResponse.ok(data));
    }

    private void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, ApiResponse payload) throws Exception {
        byte[] content = objectMapper.writeValueAsBytes(payload);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(content)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.headers().set("Access-Control-Allow-Origin", "*");
        ctx.writeAndFlush(response);
    }

    private String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    private String resolveCandidateIp(ChannelHandlerContext ctx) {
        if (ctx == null || !(ctx.channel().localAddress() instanceof InetSocketAddress)) {
            return "127.0.0.1";
        }
        InetSocketAddress address = (InetSocketAddress) ctx.channel().localAddress();
        if (address.getAddress() == null) {
            return "127.0.0.1";
        }
        String host = address.getAddress().getHostAddress();
        if (host == null || host.trim().isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)) {
            return "127.0.0.1";
        }
        return host;
    }

    private int resolveCandidatePort(ChannelHandlerContext ctx) {
        if (candidatePort > 0) {
            return candidatePort;
        }
        if (ctx == null || !(ctx.channel().localAddress() instanceof InetSocketAddress)) {
            return 9;
        }
        InetSocketAddress address = (InetSocketAddress) ctx.channel().localAddress();
        return address.getPort() > 0 ? address.getPort() : 9;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String randomToken(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

}
