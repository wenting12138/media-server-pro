package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.bootstrap.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.stats.InMemoryTrafficStatsService;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import com.wenting.mediaserver.core.stats.TrafficSnapshot;
import com.wenting.mediaserver.core.stats.TrafficStatsService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RtspBootstrap implements IServerBootstrap {
    private static final long TRAFFIC_LOG_INTERVAL_SECONDS = 5;

    private final Logger log = LoggerFactory.getLogger(RtspBootstrap.class);
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final MediaServerConfig config;
    private final StreamRegistry registry;
    private final RtpPortAllocator rtpPortAllocator;
    private final RtpUdpChannelManager rtpUdpChannelManager;
    private final TrafficStatsService trafficStatsService;
    private final RtspSessionManager sessionManager;
    private final ScheduledExecutorService trafficLogExecutor = Executors.newSingleThreadScheduledExecutor();
    private Channel rtspChannel;

    public RtspBootstrap(MediaServerConfig config, StreamRegistry registry) {
        this.config = config;
        this.trafficStatsService = new InMemoryTrafficStatsService();
        this.sessionManager = registry.getRtspSessionManager();
        this.registry = registry;
        this.rtpPortAllocator = new RtpPortAllocator(config.rtpPortMin(), config.rtpPortMax());
        this.rtpUdpChannelManager = new RtpUdpChannelManager(worker, this.registry, trafficStatsService);
    }

    @Override
    public void start() throws InterruptedException {
        ServerBootstrap rtsp = new ServerBootstrap();
        rtsp.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new RtspChannelInitializer(
                        registry,
                        rtpPortAllocator,
                        rtpUdpChannelManager,
                        trafficStatsService,
                        sessionManager
                ));
        this.rtspChannel = rtsp.bind(config.rtspPort()).sync().channel();
        log.info("RTSP (TCP signaling + RTP TCP/UDP) listening on {}", rtspChannel.localAddress());
        startTrafficLogTask();
    }

    @Override
    public void await() throws InterruptedException {
        this.rtspChannel.closeFuture().sync();
    }

    @Override
    public void close() {
        this.trafficLogExecutor.shutdownNow();
        if (this.rtspChannel != null) {
            this.rtspChannel.close();
        }
        this.worker.shutdownGracefully();
        this.boss.shutdownGracefully();
    }

    private void startTrafficLogTask() {
        trafficLogExecutor.scheduleAtFixedRate(() -> {
            try {
                logTrafficStats();
            } catch (Exception e) {
                log.warn("Failed to print traffic stats: {}", e.toString());
            }
        }, TRAFFIC_LOG_INTERVAL_SECONDS, TRAFFIC_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void logTrafficStats() {
        TrafficSnapshot global = trafficStatsService.globalSnapshot();
        TrafficSnapshot rtspControl = trafficStatsService.protocolSnapshot(TrafficProtocol.RTSP_CONTROL);
        TrafficSnapshot rtpUdp = trafficStatsService.protocolSnapshot(TrafficProtocol.RTP_UDP);
        TrafficSnapshot rtcpUdp = trafficStatsService.protocolSnapshot(TrafficProtocol.RTCP_UDP);
        TrafficSnapshot rtpTcp = trafficStatsService.protocolSnapshot(TrafficProtocol.RTP_TCP_INTERLEAVED);
        TrafficSnapshot rtcpTcp = trafficStatsService.protocolSnapshot(TrafficProtocol.RTCP_TCP_INTERLEAVED);

        if (sessionManager.count() == 0) {
            return;
        }
        log.info(
                "Traffic stats: sessions={} global(bytes={}, packets={}) rtsp(bytes={}, packets={}) "
                        + "rtpUdp(bytes={}, packets={}) rtcpUdp(bytes={}, packets={}) "
                        + "rtpTcp(bytes={}, packets={}) rtcpTcp(bytes={}, packets={})",
                sessionManager.count(),
                global.bytes(), global.packets(),
                rtspControl.bytes(), rtspControl.packets(),
                rtpUdp.bytes(), rtpUdp.packets(),
                rtcpUdp.bytes(), rtcpUdp.packets(),
                rtpTcp.bytes(), rtpTcp.packets(),
                rtcpTcp.bytes(), rtcpTcp.packets()
        );
    }


}
