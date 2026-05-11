package com.wenting.mediaserver.protocol.webrtc;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;

public final class NettyWebRtcDatagramSender implements WebRtcDatagramSender {

    private volatile Channel channel;

    public void channel(Channel channel) {
        this.channel = channel;
    }

    @Override
    public void send(byte[] payload, InetSocketAddress remoteAddress) {
        if (payload == null || payload.length == 0 || remoteAddress == null) {
            return;
        }
        Channel currentChannel = channel;
        if (currentChannel == null || !currentChannel.isActive()) {
            return;
        }
        currentChannel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(payload), remoteAddress));
    }
}
