package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.RtspTcpFramingDecoder;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.rtsp.RtspEncoder;

/**
 * RTSP over TCP: framing decoder, response encoder, session handler (push/pull + RTP relay + H264 depacketize).
 */
public final class RtspChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final StreamRegistry registry;
    private final RtpPortAllocator rtpPortAllocator;
    private final RtpUdpChannelManager rtpUdpChannelManager;
    private final TrafficStatsService trafficStatsService;
    private final RtspSessionManager sessionManager;

    public RtspChannelInitializer(
            StreamRegistry registry,
            RtpPortAllocator rtpPortAllocator,
            RtpUdpChannelManager rtpUdpChannelManager,
            TrafficStatsService trafficStatsService,
            RtspSessionManager sessionManager
    ) {
        this.registry = registry;
        this.rtpPortAllocator = rtpPortAllocator;
        this.rtpUdpChannelManager = rtpUdpChannelManager;
        this.trafficStatsService = trafficStatsService;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new RtspTcpFramingDecoder());
        ch.pipeline().addLast(new RtspEncoder());
        ch.pipeline().addLast(new RtspConnectionHandler(
                registry,
                rtpPortAllocator,
                rtpUdpChannelManager,
                trafficStatsService,
                sessionManager
        ));
    }
}
