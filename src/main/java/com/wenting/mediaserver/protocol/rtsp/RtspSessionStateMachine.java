package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.InterleavedRtpPacket;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.codec.rtsp.RtspRequestMessage;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.rtsp.RtspRequestType;
import com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole;
import com.wenting.mediaserver.core.enums.rtsp.RtspSessionState;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.enums.traffic.TrafficDirection;
import com.wenting.mediaserver.core.stats.TrafficEvent;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explicit RTSP session state machine for one TCP connection.
 */
public final class RtspSessionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RtspSessionStateMachine.class);

    private final StreamRegistry registry;
    private final RtspSession session;
    private final RtpPortAllocator rtpPortAllocator;
    private final RtpUdpChannelManager rtpUdpChannelManager;
    private final TrafficStatsService trafficStatsService;
    private final RtspSessionManager sessionManager;

    public RtspSessionStateMachine(
            StreamRegistry registry,
            RtspSession session,
            RtpPortAllocator rtpPortAllocator,
            RtpUdpChannelManager rtpUdpChannelManager,
            TrafficStatsService trafficStatsService,
            RtspSessionManager sessionManager
    ) {
        this.registry = registry;
        this.session = session == null ? new RtspSession() : session;
        this.rtpPortAllocator = rtpPortAllocator;
        this.rtpUdpChannelManager = rtpUdpChannelManager;
        this.trafficStatsService = trafficStatsService;
        this.sessionManager = sessionManager;
    }

    public RtspSession session() {
        return session;
    }

    public void onChannelInactive() {
        unregisterSubscriber();
        unregisterPublishedStream();
        releaseTransportPorts();
        session.close();
    }

    public void handleRequest(ChannelHandlerContext ctx, RtspRequestMessage request) {
        session.touch();
        bindUpstreamControlSender(ctx);
        recordControlInbound(request);
        RtspRequestType requestType = RtspRequestType.fromMethod(request.method());
        log.info("RTSP {} session={} state={} uri={}", requestType.methodName(), session.sessionId(), session.state(), request.uri());

        if (requestType == RtspRequestType.UNKNOWN) {
            writeResponse(ctx, request, RtspResponseStatuses.NOT_IMPLEMENTED);
            return;
        }
        if (requestType == RtspRequestType.OPTIONS) {
            handleOptions(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.TEARDOWN) {
            handleTeardown(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.GET_PARAMETER || requestType == RtspRequestType.SET_PARAMETER) {
            handleKeepAlive(ctx, request);
            return;
        }

        switch (session.state()) {
            case INIT:
                handleInitState(ctx, request, requestType);
                return;
            case ANNOUNCED:
                handleAnnouncedState(ctx, request, requestType);
                return;
            case READY:
                handleReadyState(ctx, request, requestType);
                return;
            case RECORDING:
                handleRecordingState(ctx, request, requestType);
                return;
            case PLAYING:
                handlePlayingState(ctx, request, requestType);
                return;
            case CLOSED:
                writeResponse(ctx, request, RtspResponseStatuses.SESSION_NOT_FOUND);
                return;
            default:
                writeResponse(ctx, request, RtspResponseStatuses.INTERNAL_SERVER_ERROR);
        }
    }

    public void handleInterleavedPacket(InterleavedRtpPacket packet) {
        session.touch();
        if (!session.usesInterleavedTcp()) {
            log.debug("Dropping interleaved packet before TCP transport setup: session={}", session.sessionId());
            return;
        }
        RtspTransport transport = session.findTransportByInterleavedChannel(packet.channel());
        if (transport == null) {
            log.debug("Dropping interleaved packet on unbound channel {} for session={}", packet.channel(), session.sessionId());
            return;
        }
        if (session.state() != RtspSessionState.RECORDING && session.state() != RtspSessionState.PLAYING) {
            log.debug("Dropping interleaved packet in state {} for session={}", session.state(), session.sessionId());
            return;
        }
        recordInterleavedInbound(packet, transport);
        routeInterleavedPacket(packet, transport);
        log.debug("Interleaved packet channel={} bytes={} session={}",
                packet.channel(), packet.payload().readableBytes(), session.sessionId());
    }

    private void handleInitState(ChannelHandlerContext ctx, RtspRequestMessage request, RtspRequestType requestType) {
        if (requestType == RtspRequestType.ANNOUNCE) {
            transitionToAnnounced(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.DESCRIBE) {
            prepareSubscriber(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.SETUP) {
            configureTransport(ctx, request);
            return;
        }
        writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
    }

    private void handleAnnouncedState(ChannelHandlerContext ctx, RtspRequestMessage request, RtspRequestType requestType) {
        if (requestType == RtspRequestType.SETUP) {
            configureTransport(ctx, request);
            return;
        }
        writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
    }

    private void handleReadyState(ChannelHandlerContext ctx, RtspRequestMessage request, RtspRequestType requestType) {
        if (requestType == RtspRequestType.SETUP) {
            configureTransport(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.RECORD) {
            startRecording(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.PLAY) {
            startPlayback(ctx, request);
            return;
        }
        writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
    }

    private void handleRecordingState(ChannelHandlerContext ctx, RtspRequestMessage request, RtspRequestType requestType) {
        if (requestType == RtspRequestType.SETUP) {
            configureTransport(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.RECORD) {
            writeResponse(ctx, request, RtspResponseStatuses.OK);
            return;
        }
        writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
    }

    private void handlePlayingState(ChannelHandlerContext ctx, RtspRequestMessage request, RtspRequestType requestType) {
        if (requestType == RtspRequestType.SETUP) {
            configureTransport(ctx, request);
            return;
        }
        if (requestType == RtspRequestType.PLAY) {
            writeResponse(ctx, request, RtspResponseStatuses.OK);
            return;
        }
        writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
    }

    private void handleOptions(ChannelHandlerContext ctx, RtspRequestMessage request) {
        FullHttpResponse response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        populateCommonHeaders(response, request);
        response.headers().set(RtspHeaderNames.PUBLIC,
                "OPTIONS, ANNOUNCE, DESCRIBE, SETUP, RECORD, PLAY, TEARDOWN, GET_PARAMETER, SET_PARAMETER");
        ctx.writeAndFlush(response);
    }

    private void transitionToAnnounced(ChannelHandlerContext ctx, RtspRequestMessage request) {
        session.role(RtspSessionRole.PUBLISHER);
        session.streamKey(RtspHelper.parseStreamKey(request.uri()));
        session.sdpOrigin(request.body().toString(CharsetUtil.UTF_8));
        session.state(RtspSessionState.ANNOUNCED);
        log.info("sdp: \r\n{}", session.sdpOrigin());
        writeResponse(ctx, request, RtspResponseStatuses.OK);
    }

    private void prepareSubscriber(ChannelHandlerContext ctx, RtspRequestMessage request) {
        session.role(RtspSessionRole.SUBSCRIBER);
        session.streamKey(RtspHelper.parseStreamKey(request.uri()));
        IPublishedStream publishedStream = findPublishedStreamForSession();
        if (publishedStream == null) {
            writeResponse(ctx, request, RtspResponseStatuses.NOT_FOUND);
            return;
        }
        RtspSession publisherSession = findPublisherSession();
        String sdp = publisherSession == null ? null : publisherSession.sdpOrigin();
        if (sdp == null || sdp.isEmpty()) {
            sdp = publishedStream.sdpDescription(session.streamKey());
        }
        if (sdp == null || sdp.isEmpty()) {
            writeResponse(ctx, request, RtspResponseStatuses.NOT_FOUND);
            return;
        }
        session.sdpOrigin(sdp);
        FullHttpResponse response = new DefaultFullHttpResponse(
                RtspVersions.RTSP_1_0,
                RtspResponseStatuses.OK,
                Unpooled.copiedBuffer(sdp, CharsetUtil.UTF_8)
        );
        populateCommonHeaders(response, request);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/sdp");
        response.headers().set(RtspHeaderNames.CONTENT_BASE, request.uri());
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        recordControlOutbound(response);
        ctx.writeAndFlush(response);
    }

    private void configureTransport(ChannelHandlerContext ctx, RtspRequestMessage request) {
        if (!session.hasStreamKey()) {
            session.streamKey(RtspHelper.parseStreamKey(request.uri()));
        }
        String trackId = RtspHelper.parseTrackId(request.uri(), session.streamKey());
        RtspTransport transport = RtspHelper.parseTransport(request.header("Transport"));
        if (transport.usesUdp() && rtpPortAllocator != null) {
            RtpPortAllocation allocation = rtpPortAllocator.allocate();
            try {
                if (rtpUdpChannelManager != null) {
                    rtpUdpChannelManager.bind(allocation,
                            new RtspUdpBinding(session.sessionId(), session.streamKey(), trackId, false),
                            new RtspUdpBinding(session.sessionId(), session.streamKey(), trackId, true)
                    );
                }
                transport = transport.withServerPorts(allocation);
            } catch (InterruptedException e) {
                rtpPortAllocator.release(allocation);
                Thread.currentThread().interrupt();
                writeResponse(ctx, request, RtspResponseStatuses.INTERNAL_SERVER_ERROR);
                return;
            } catch (RuntimeException e) {
                rtpPortAllocator.release(allocation);
                writeResponse(ctx, request, RtspResponseStatuses.INTERNAL_SERVER_ERROR);
                return;
            }
        }
        session.transport(trackId, transport);
        session.state(RtspSessionState.READY);
        FullHttpResponse response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        populateCommonHeaders(response, request);
        response.headers().set(RtspHeaderNames.SESSION, session.sessionId());
        if (transport.toResponseHeaderValue() != null) {
            response.headers().set(RtspHeaderNames.TRANSPORT, transport.toResponseHeaderValue());
        }
        ctx.writeAndFlush(response);
    }

    private void startRecording(ChannelHandlerContext ctx, RtspRequestMessage request) {
        if (!session.isPublisher()) {
            writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
            return;
        }
        registerPublishedStream();
        session.state(RtspSessionState.RECORDING);
        writeResponse(ctx, request, RtspResponseStatuses.OK);
    }

    private void startPlayback(ChannelHandlerContext ctx, RtspRequestMessage request) {
        if (!session.isSubscriber()) {
            writeResponse(ctx, request, RtspResponseStatuses.METHOD_NOT_VALID);
            return;
        }
        if (!registerSubscriber(ctx)) {
            writeResponse(ctx, request, RtspResponseStatuses.NOT_FOUND);
            return;
        }
        session.state(RtspSessionState.PLAYING);
        writeResponse(ctx, request, RtspResponseStatuses.OK);
    }

    private void handleTeardown(ChannelHandlerContext ctx, RtspRequestMessage request) {
        unregisterSubscriber();
        unregisterPublishedStream();
        releaseTransportPorts();
        session.close();
        writeResponse(ctx, request, RtspResponseStatuses.OK);
        ctx.close();
    }

    private void handleKeepAlive(ChannelHandlerContext ctx, RtspRequestMessage request) {
        writeResponse(ctx, request, RtspResponseStatuses.OK);
    }

    private void writeResponse(ChannelHandlerContext ctx, RtspRequestMessage request, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, status);
        populateCommonHeaders(response, request);
        recordControlOutbound(response);
        ctx.writeAndFlush(response);
    }

    private void populateCommonHeaders(FullHttpResponse response, RtspRequestMessage request) {
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        String cSeq = request.header("CSeq");
        if (cSeq != null) {
            response.headers().set(RtspHeaderNames.CSEQ, cSeq);
        }
        response.headers().set(RtspHeaderNames.SERVER, "media-server-pro");
        if (session.state() != RtspSessionState.INIT) {
            response.headers().set(RtspHeaderNames.SESSION, session.sessionId());
        }
    }

    private void releaseTransportPorts() {
        if (rtpPortAllocator == null) {
            return;
        }
        for (RtspTransport transport : session.transportsByTrackId().values()) {
            if (transport == null || !transport.usesUdp()) {
                continue;
            }
            if (transport.serverRtpPort() == null || transport.serverRtcpPort() == null) {
                continue;
            }
            RtpPortAllocation allocation = new RtpPortAllocation(transport.serverRtpPort(), transport.serverRtcpPort());
            if (rtpUdpChannelManager != null) {
                rtpUdpChannelManager.release(allocation);
            }
            rtpPortAllocator.release(allocation);
        }
    }

    private void registerPublishedStream() {
        if (!session.hasStreamKey()) {
            return;
        }
        IPublishedStream existing = registry.findPublishedStream(session.streamKey());
        if (existing == null) {
            registry.registerPublishedStream(session.streamKey(), new DefaultPublishedStream(session.streamKey()));
        }
    }

    private void unregisterPublishedStream() {
        if (session.isPublisher() && session.hasStreamKey()) {
            registry.removePublishedStream(session.streamKey());
        }
    }

    private boolean registerSubscriber(ChannelHandlerContext ctx) {
        if (!session.isSubscriber() || !session.hasStreamKey()) {
            return false;
        }
        IPublishedStream stream = findPublishedStreamForSession();
        if (stream == null) {
            return false;
        }
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                session.sessionId(),
                session.streamKey(),
                ctx.channel(),
                rtpUdpChannelManager
        );
        for (ITrack track : session.trackList()) {
            subscriber.track(track);
        }
        for (java.util.Map.Entry<String, RtspTransport> entry : session.transportsByTrackId().entrySet()) {
            subscriber.transport(entry.getKey(), entry.getValue());
        }
        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));
        return true;
    }

    private void unregisterSubscriber() {
        if (!session.isSubscriber() || !session.hasStreamKey()) {
            return;
        }
        IPublishedStream stream = findPublishedStreamForSession();
        if (stream != null) {
            stream.removeSubscriber(session.sessionId());
        }
    }

    private IPublishedStream findPublishedStreamForSession() {
        if (!session.hasStreamKey()) {
            return null;
        }
        if (session.isSubscriber()) {
            return registry.findPublishedStreamForRtspPlayback(
                    session.streamKey().app(),
                    session.streamKey().stream()
            );
        }
        IPublishedStream stream = registry.findPublishedStream(session.streamKey());
        if (stream != null) {
            return stream;
        }
        return registry.findPublishedStreamByPath(session.streamKey().app(), session.streamKey().stream());
    }

    private void routeInterleavedPacket(InterleavedRtpPacket packet, RtspTransport transport) {
        if (!session.hasStreamKey()) {
            return;
        }
        IPublishedStream stream = registry.findPublishedStream(session.streamKey());
        if (stream == null) {
            return;
        }
        String trackId = findTrackIdForTransport(transport);
        boolean isRtcp = transport.interleavedRtcpChannel() != null
                && transport.interleavedRtcpChannel().intValue() == packet.channel();
        ITrack track = session.findTrack(trackId);
        InboundRtpPacket inboundPacket = new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        track == null ? TrackType.UNKNOWN : track.trackType(),
                        track == null ? CodecType.UNKNOWN : track.codecType(),
                        session.sessionId(),
                        session.streamKey(),
                        trackId,
                        null,
                        null,
                        false,
                        false,
                        track != null && track.outOfBandParameterSetsReady(),
                        null,
                        ByteBufUtil.getBytes(packet.payload(), packet.payload().readerIndex(), packet.payload().readableBytes(), false)
                ),
                track == null ? 0 : track.clockRate(),
                isRtcp,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(packet.channel())
        );
        stream.onInboundRtpPacket(inboundPacket);
    }

    private void recordControlInbound(RtspRequestMessage request) {
        if (trafficStatsService == null || request == null) {
            return;
        }
        trafficStatsService.record(new TrafficEvent(
                TrafficDirection.INBOUND,
                TrafficProtocol.RTSP_CONTROL,
                session.streamKey(),
                session.sessionId(),
                "",
                RtspHelper.estimateRequestBytes(request),
                1,
                System.currentTimeMillis()
        ));
    }

    private void recordControlOutbound(FullHttpResponse response) {
        if (trafficStatsService == null || response == null) {
            return;
        }
        trafficStatsService.record(new TrafficEvent(
                TrafficDirection.OUTBOUND,
                TrafficProtocol.RTSP_CONTROL,
                session.streamKey(),
                session.sessionId(),
                "",
                RtspHelper.estimateResponseBytes(response),
                1,
                System.currentTimeMillis()
        ));
    }

    private void recordInterleavedInbound(InterleavedRtpPacket packet, RtspTransport transport) {
        if (trafficStatsService == null || packet == null || transport == null) {
            return;
        }
        String trackId = findTrackIdForTransport(transport);
        boolean isRtcp = transport.interleavedRtcpChannel() != null
                && transport.interleavedRtcpChannel().intValue() == packet.channel();
        trafficStatsService.record(new TrafficEvent(
                TrafficDirection.INBOUND,
                isRtcp ? TrafficProtocol.RTCP_TCP_INTERLEAVED : TrafficProtocol.RTP_TCP_INTERLEAVED,
                session.streamKey(),
                session.sessionId(),
                trackId,
                packet.payload().readableBytes(),
                1,
                System.currentTimeMillis()
        ));
    }

    private String findTrackIdForTransport(RtspTransport target) {
        for (java.util.Map.Entry<String, RtspTransport> entry : session.transportsByTrackId().entrySet()) {
            if (entry.getValue() == target) {
                return entry.getKey();
            }
        }
        return "";
    }

    private RtspSession findPublisherSession() {
        if (sessionManager == null || !session.hasStreamKey()) {
            return null;
        }
        for (RtspSession candidate : sessionManager.sessions()) {
            if (candidate == null || !candidate.isPublisher()) {
                continue;
            }
            if (session.streamKey().equals(candidate.streamKey())) {
                return candidate;
            }
        }
        return null;
    }

    private void bindUpstreamControlSender(ChannelHandlerContext ctx) {
        if (ctx == null) {
            return;
        }
        final Channel controlChannel = ctx.channel();
        session.upstreamControlSender(new RtspUpstreamControlSender() {
            @Override
            public boolean requestVideoKeyFrame(String trackId, long mediaSsrc) {
                return sendUpstreamPli(controlChannel, trackId, mediaSsrc);
            }
        });
    }

    private boolean sendUpstreamPli(Channel controlChannel, String trackId, long mediaSsrc) {
        if (!session.isPublisher()) {
            return false;
        }
        RtspTransport transport = session.transport(trackId);
        if (transport == null) {
            return false;
        }
        ITrack track = session.findTrack(trackId);
        if (track == null || track.trackType() != TrackType.VIDEO) {
            return false;
        }
        byte[] pli = buildPictureLossIndication(mediaSsrc);
        if (transport.usesInterleavedTcp()) {
            Integer channel = transport.interleavedRtcpChannel();
            if (channel == null || controlChannel == null || !controlChannel.isActive()) {
                return false;
            }
            io.netty.buffer.ByteBuf out = Unpooled.buffer(4 + pli.length);
            out.writeByte('$');
            out.writeByte(channel.intValue());
            out.writeShort(pli.length);
            out.writeBytes(pli);
            controlChannel.writeAndFlush(out);
            recordOutboundRtcpTcp(trackId, pli.length);
            log.info("Sent RTSP upstream PLI over TCP session={} track={} mediaSsrc={} channel={}",
                    session.sessionId(), trackId, mediaSsrc, channel);
            return true;
        }
        if (transport.usesUdp() && rtpUdpChannelManager != null) {
            Integer clientRtcpPort = transport.clientRtcpPort();
            Integer serverRtcpPort = transport.serverRtcpPort();
            if (clientRtcpPort == null || serverRtcpPort == null) {
                return false;
            }
            java.net.InetSocketAddress remoteAddress = resolvePublisherRemoteAddress(controlChannel, track, clientRtcpPort.intValue());
            if (remoteAddress == null) {
                return false;
            }
            InboundRtpPacket packet = new InboundRtpPacket(
                    new InboundMediaFrame(
                            StreamProtocol.RTSP,
                            TrackType.VIDEO,
                            track.codecType(),
                            session.sessionId(),
                            session.streamKey(),
                            trackId,
                            null,
                            null,
                            false,
                            false,
                            track.outOfBandParameterSetsReady(),
                            remoteAddress,
                            pli
                    ),
                    track.clockRate(),
                    true,
                    MediaPacketTransport.UDP,
                    Integer.valueOf(serverRtcpPort.intValue()),
                    null
            );
            boolean sent = rtpUdpChannelManager.send(packet, serverRtcpPort.intValue(), remoteAddress);
            if (sent) {
                log.info("Sent RTSP upstream PLI over UDP session={} track={} mediaSsrc={} localPort={} remote={}",
                        session.sessionId(), trackId, mediaSsrc, serverRtcpPort, remoteAddress);
            }
            return sent;
        }
        return false;
    }

    private java.net.InetSocketAddress resolvePublisherRemoteAddress(Channel controlChannel, ITrack track, int clientRtcpPort) {
        if (track != null && isUsableConnectionAddress(track.connectionAddress())) {
            return new java.net.InetSocketAddress(track.connectionAddress(), clientRtcpPort);
        }
        if (controlChannel == null || !(controlChannel.remoteAddress() instanceof java.net.InetSocketAddress)) {
            return null;
        }
        java.net.InetSocketAddress remoteAddress = (java.net.InetSocketAddress) controlChannel.remoteAddress();
        return new java.net.InetSocketAddress(remoteAddress.getAddress(), clientRtcpPort);
    }

    private boolean isUsableConnectionAddress(String connectionAddress) {
        if (connectionAddress == null) {
            return false;
        }
        String trimmed = connectionAddress.trim();
        return !trimmed.isEmpty()
                && !"0.0.0.0".equals(trimmed)
                && !"::".equals(trimmed);
    }

    private byte[] buildPictureLossIndication(long mediaSsrc) {
        byte[] packet = new byte[12];
        packet[0] = (byte) 0x81;
        packet[1] = (byte) 206;
        packet[2] = 0x00;
        packet[3] = 0x02;
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x00;
        packet[7] = 0x01;
        packet[8] = (byte) ((mediaSsrc >>> 24) & 0xFF);
        packet[9] = (byte) ((mediaSsrc >>> 16) & 0xFF);
        packet[10] = (byte) ((mediaSsrc >>> 8) & 0xFF);
        packet[11] = (byte) (mediaSsrc & 0xFF);
        return packet;
    }

    private void recordOutboundRtcpTcp(String trackId, int bytes) {
        if (trafficStatsService == null) {
            return;
        }
        trafficStatsService.record(new TrafficEvent(
                TrafficDirection.OUTBOUND,
                TrafficProtocol.RTCP_TCP_INTERLEAVED,
                session.streamKey(),
                session.sessionId(),
                trackId,
                bytes,
                1,
                System.currentTimeMillis()
        ));
    }

}
