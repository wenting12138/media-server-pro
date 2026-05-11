package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.bootstrap.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebRtcUdpBootstrap implements IServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WebRtcUdpBootstrap.class);

    private final MediaServerConfig config;
    private final WebRtcSessionManager sessionManager;
    private final NettyWebRtcDatagramSender datagramSender;
    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
    private Channel udpChannel;

    public WebRtcUdpBootstrap(
            MediaServerConfig config,
            WebRtcSessionManager sessionManager,
            NettyWebRtcDatagramSender datagramSender
    ) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.datagramSender = datagramSender == null ? new NettyWebRtcDatagramSender() : datagramSender;
    }

    @Override
    public void start() throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .handler(new WebRtcUdpPacketHandler(sessionManager, datagramSender));
        this.udpChannel = bootstrap.bind(config.webrtcUdpPort()).sync().channel();
        datagramSender.channel(udpChannel);
        log.info("WebRTC UDP listening on {}", udpChannel.localAddress());
    }

    @Override
    public void await() throws InterruptedException {
        if (udpChannel != null) {
            udpChannel.closeFuture().sync();
        }
    }

    @Override
    public void close() {
        if (udpChannel != null) {
            udpChannel.close();
        }
        datagramSender.channel(null);
        eventLoopGroup.shutdownGracefully();
    }
}
