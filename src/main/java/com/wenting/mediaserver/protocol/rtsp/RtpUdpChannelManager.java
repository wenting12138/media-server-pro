package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.enums.traffic.TrafficDirection;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.stats.TrafficEvent;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds UDP RTP/RTCP ports on demand and routes received packets into the stream registry.
 */
public final class RtpUdpChannelManager implements RtpUdpPacketSender {

    private final EventLoopGroup eventLoopGroup;
    private final StreamRegistry registry;
    private final TrafficStatsService trafficStatsService;
    private final Map<Integer, Channel> channelsByPort = new ConcurrentHashMap<Integer, Channel>();

    public RtpUdpChannelManager(EventLoopGroup eventLoopGroup, StreamRegistry registry, TrafficStatsService trafficStatsService) {
        this.eventLoopGroup = eventLoopGroup;
        this.registry = registry;
        this.trafficStatsService = trafficStatsService;
    }

    public synchronized void bind(RtpPortAllocation allocation, RtspUdpBinding rtpBinding, RtspUdpBinding rtcpBinding) throws InterruptedException {
        if (allocation == null || rtpBinding == null || rtcpBinding == null) {
            throw new IllegalArgumentException("allocation and bindings must not be null");
        }
        bindPort(allocation.rtpPort(), rtpBinding);
        try {
            bindPort(allocation.rtcpPort(), rtcpBinding);
        } catch (InterruptedException e) {
            release(allocation);
            throw e;
        } catch (RuntimeException e) {
            release(allocation);
            throw e;
        }
    }

    public synchronized void release(RtpPortAllocation allocation) {
        if (allocation == null) {
            return;
        }
        releasePort(allocation.rtpPort());
        releasePort(allocation.rtcpPort());
    }

    @Override
    public boolean send(InboundRtpPacket packet, int localPort, InetSocketAddress remoteAddress) {
        if (packet == null || localPort <= 0 || remoteAddress == null) {
            return false;
        }
        Channel channel = channelsByPort.get(localPort);
        if (channel == null || !channel.isActive()) {
            return false;
        }
        channel.writeAndFlush(new DatagramPacket(channel.alloc().buffer(packet.frame().payloadLength()).writeBytes(packet.frame().payload()), remoteAddress));
        recordOutbound(packet);
        return true;
    }

    private void bindPort(int port, RtspUdpBinding binding) throws InterruptedException {
        if (channelsByPort.containsKey(port)) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .handler(new RtpUdpPacketHandler(registry, port, trafficStatsService));
        ChannelFuture future = bootstrap.bind(port).sync();
        channelsByPort.put(port, future.channel());
        registry.bindUdpPort(port, binding);
    }

    private void releasePort(int port) {
        Channel channel = channelsByPort.remove(port);
        registry.unbindUdpPort(port);
        if (channel != null) {
            channel.close();
        }
    }

    private void recordOutbound(InboundRtpPacket packet) {
        if (trafficStatsService == null || packet == null) {
            return;
        }
        trafficStatsService.record(new TrafficEvent(
                TrafficDirection.OUTBOUND,
                packet.rtcp() ? TrafficProtocol.RTCP_UDP : TrafficProtocol.RTP_UDP,
                packet.frame().streamKey(),
                packet.frame().sessionId(),
                packet.frame().trackId(),
                packet.frame().payloadLength(),
                1,
                System.currentTimeMillis()
        ));
    }
}
