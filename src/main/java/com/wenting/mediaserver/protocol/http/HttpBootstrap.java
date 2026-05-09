package com.wenting.mediaserver.protocol.http;

import com.wenting.mediaserver.api.HttpJsonApiHandler;
import com.wenting.mediaserver.config.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
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

public class HttpBootstrap implements IServerBootstrap {
    private final Logger log = LoggerFactory.getLogger(HttpBootstrap.class);
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final MediaServerConfig config;
    private final StreamRegistry registry;
    private Channel httpChannel;

    public HttpBootstrap(MediaServerConfig config, StreamRegistry registry) {
        this.config = config;
        this.registry = registry;
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
                        ch.pipeline().addLast(new HttpFlvHandler(registry));
                        ch.pipeline().addLast(new HttpJsonApiHandler(config));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 512)
                .childOption(ChannelOption.TCP_NODELAY, true);
        this.httpChannel = http.bind(config.httpPort()).sync().channel();
        log.info("HTTP API listening on {}", httpChannel.localAddress());
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
        this.worker.shutdownGracefully();
        this.boss.shutdownGracefully();
    }

}
