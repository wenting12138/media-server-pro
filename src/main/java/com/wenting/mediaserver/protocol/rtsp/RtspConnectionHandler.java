package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.InterleavedRtpPacket;
import com.wenting.mediaserver.core.codec.rtsp.RtspRequestMessage;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty bridge that delegates RTSP connection flow to a dedicated state machine.
 */
public final class RtspConnectionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(RtspConnectionHandler.class);

    private final RtspSessionStateMachine stateMachine;
    private final RtspSessionManager sessionManager;

    public RtspConnectionHandler(StreamRegistry registry) {
        this(registry, null, null, null, resolveSessionManager(registry, null));
    }

    public RtspConnectionHandler(StreamRegistry registry, RtpPortAllocator rtpPortAllocator) {
        this(registry, rtpPortAllocator, null, null, resolveSessionManager(registry, null));
    }

    public RtspConnectionHandler(
            StreamRegistry registry,
            RtpPortAllocator rtpPortAllocator,
            RtpUdpChannelManager rtpUdpChannelManager,
            TrafficStatsService trafficStatsService
    ) {
        this(registry, rtpPortAllocator, rtpUdpChannelManager, trafficStatsService, resolveSessionManager(registry, null));
    }

    public RtspConnectionHandler(
            StreamRegistry registry,
            RtpPortAllocator rtpPortAllocator,
            RtpUdpChannelManager rtpUdpChannelManager,
            TrafficStatsService trafficStatsService,
            RtspSessionManager sessionManager
    ) {
        this.sessionManager = resolveSessionManager(registry, sessionManager);
        this.stateMachine = new RtspSessionStateMachine(
                registry,
                this.sessionManager.createSession(),
                rtpPortAllocator,
                rtpUdpChannelManager,
                trafficStatsService,
                this.sessionManager
        );
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        stateMachine.onChannelInactive();
        sessionManager.remove(stateMachine.session().sessionId());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("RTSP connection error: {}", cause.toString());
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof RtspRequestMessage) {
            RtspRequestMessage request = (RtspRequestMessage) msg;
            try {
                stateMachine.handleRequest(ctx, request);
            } finally {
                releaseRequest(request);
            }
        } else if (msg instanceof InterleavedRtpPacket) {
            InterleavedRtpPacket packet = (InterleavedRtpPacket) msg;
            try {
                stateMachine.handleInterleavedPacket(packet);
            } finally {
                packet.release();
            }
        } else {
            log.debug("Ignoring unsupported RTSP inbound message type: {}", msg.getClass().getName());
        }
    }

    private static void releaseRequest(RtspRequestMessage request) {
        if (request != null && request.body() != null && request.body().refCnt() > 0) {
            request.body().release();
        }
    }

    private static RtspSessionManager resolveSessionManager(StreamRegistry registry, RtspSessionManager explicitManager) {
        if (explicitManager != null) {
            return explicitManager;
        }
        if (registry != null && registry.getRtspSessionManager() != null) {
            return registry.getRtspSessionManager();
        }
        return new RtspSessionManager();
    }
}
