package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.codec.rtmp.RtmpChunkDecoder;
import com.wenting.mediaserver.core.codec.rtmp.RtmpHandshakeHandler;
import com.wenting.mediaserver.core.codec.rtmp.RtmpMessageEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

public final class RtmpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final StreamRegistry streamRegistry;
    private final RtmpSessionManager sessionManager;

    public RtmpChannelInitializer(StreamRegistry streamRegistry, RtmpSessionManager sessionManager) {
        this.streamRegistry = streamRegistry;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new RtmpHandshakeHandler());
        pipeline.addLast(new RtmpChunkDecoder());
        pipeline.addLast(new RtmpMessageEncoder());
        pipeline.addLast(new RtmpConnectionHandler(streamRegistry, sessionManager));
    }
}
