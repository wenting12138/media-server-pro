package com.wenting.mediaserver.protocol.http;

import com.wenting.mediaserver.protocol.http.api.HttpJsonApiHandler;
import com.wenting.mediaserver.protocol.http.webrtc.WebRtcPlayHandler;
import com.wenting.mediaserver.protocol.http.webrtc.WebRtcTestPageHandler;
import com.wenting.mediaserver.bootstrap.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.flv.HttpFlvHandler;
import com.wenting.mediaserver.protocol.http.hls.HlsHandler;
import com.wenting.mediaserver.protocol.http.hls.HlsSessionManager;
import com.wenting.mediaserver.protocol.webrtc.WebRtcDatagramSender;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

public class HttpBootstrap implements IServerBootstrap {
    private final Logger log = LoggerFactory.getLogger(HttpBootstrap.class);
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final MediaServerConfig config;
    private final StreamRegistry registry;
    private final HlsSessionManager hlsSessionManager;
    private final WebRtcSessionManager webRtcSessionManager;
    private final WebRtcDatagramSender webRtcDatagramSender;
    private Channel httpChannel;

    public HttpBootstrap(
            MediaServerConfig config,
            StreamRegistry registry,
            WebRtcSessionManager webRtcSessionManager,
            WebRtcDatagramSender webRtcDatagramSender
    ) {
        this.config = config;
        this.registry = registry;
        this.hlsSessionManager = config.hlsFileStorageEnabled()
                ? new HlsSessionManager(registry, Paths.get(config.hlsDirectory()))
                : new HlsSessionManager(registry);
        this.webRtcSessionManager = webRtcSessionManager == null ? new WebRtcSessionManager() : webRtcSessionManager;
        this.webRtcDatagramSender = webRtcDatagramSender;
    }

    @Override
    public void start() throws InterruptedException {
        ServerBootstrap http = new ServerBootstrap();
        http.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        ch.pipeline().addLast(new HttpRouterHandler(
                                new WebRtcTestPageHandler(),
                                new HlsHandler(registry, hlsSessionManager),
                                new HttpFlvHandler(registry),
                                new WebRtcPlayHandler(registry, webRtcSessionManager, config.webrtcUdpPort(), webRtcDatagramSender),
                                new HttpJsonApiHandler(config)
                        ));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 512)
                .childOption(ChannelOption.TCP_NODELAY, true);
        this.httpChannel = http.bind(config.httpPort()).sync().channel();
        log.info("HTTP API listening on {}", httpChannel.localAddress());
        log.info("HTTP HLS storage={} directory={}", config.hlsStorage(), config.hlsDirectory());
    }

    @Override
    public void await() throws InterruptedException {
        this.httpChannel.closeFuture().sync();
    }

    @Override
    public void close() {
        if (this.httpChannel != null) {
            this.httpChannel.close();
        }
        this.hlsSessionManager.close();
        this.worker.shutdownGracefully();
        this.boss.shutdownGracefully();
    }

}
