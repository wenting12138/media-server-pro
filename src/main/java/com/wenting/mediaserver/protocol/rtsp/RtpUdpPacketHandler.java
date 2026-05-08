package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.enums.traffic.TrafficDirection;
import com.wenting.mediaserver.core.stats.TrafficEvent;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

/**
 * Receives UDP RTP/RTCP packets on one bound local port and forwards them into the stream registry.
 */
public final class RtpUdpPacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final StreamRegistry registry;
    private final int localPort;
    private final TrafficStatsService trafficStatsService;

    public RtpUdpPacketHandler(StreamRegistry registry, int localPort, TrafficStatsService trafficStatsService) {
        this.registry = registry;
        this.localPort = localPort;
        this.trafficStatsService = trafficStatsService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        RtspUdpBinding binding = registry.findUdpBindingByPort(localPort).orElse(null);
        if (binding != null && trafficStatsService != null) {
            trafficStatsService.record(new TrafficEvent(
                    TrafficDirection.INBOUND,
                    binding.rtcp() ? TrafficProtocol.RTCP_UDP : TrafficProtocol.RTP_UDP,
                    binding.streamKey(),
                    binding.sessionId(),
                    binding.trackId(),
                    msg.content().readableBytes(),
                    1,
                    System.currentTimeMillis()
            ));
        }
        registry.onUdpPacket(localPort, msg.sender(), msg.content());
    }
}
