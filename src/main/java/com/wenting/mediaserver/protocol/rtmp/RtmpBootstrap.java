package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.bootstrap.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtmpBootstrap implements IServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(RtmpBootstrap.class);

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final MediaServerConfig config;
    private final StreamRegistry streamRegistry;
    private final RtmpSessionManager sessionManager;
    private Channel rtmpChannel;

    public RtmpBootstrap(MediaServerConfig config, StreamRegistry streamRegistry, RtmpSessionManager sessionManager) {
        this.config = config;
        this.streamRegistry = streamRegistry;
        this.sessionManager = sessionManager == null ? new RtmpSessionManager() : sessionManager;
    }

    @Override
    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new RtmpChannelInitializer(streamRegistry, sessionManager))
                .option(ChannelOption.SO_BACKLOG, 512)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);
        rtmpChannel = bootstrap.bind(config.rtmpPort()).sync().channel();
        log.info("RTMP listening on {}", rtmpChannel.localAddress());
    }

    @Override
    public void await() throws InterruptedException {
        if (rtmpChannel != null) {
            rtmpChannel.closeFuture().sync();
        }
    }

    @Override
    public void close() {
        if (rtmpChannel != null) {
            rtmpChannel.close();
        }
        worker.shutdownGracefully();
        boss.shutdownGracefully();
    }
}
