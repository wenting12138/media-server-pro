package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageCodec;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageType;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public final class WebRtcUdpPacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger log = LoggerFactory.getLogger(WebRtcUdpPacketHandler.class);

    private final WebRtcSessionManager sessionManager;
    private final IceBindingService iceBindingService = new IceBindingService();
    private final StunMessageCodec stunMessageCodec = new StunMessageCodec();

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        byte[] bytes = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), bytes);
        StunMessage message = stunMessageCodec.decode(bytes);
        if (message == null) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring non-STUN WebRTC UDP packet from {}", packet.sender());
            }
            return;
        }
        if (message.type() != StunMessageType.BINDING_REQUEST) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring unsupported STUN message type {} from {}", message.type(), packet.sender());
            }
            return;
        }
        String localUfrag = extractLocalUfrag(message.username());
        WebRtcPeerSession session = sessionManager.findByLocalUfrag(localUfrag);
        if (session == null || session.iceAgent() == null) {
            if (log.isDebugEnabled()) {
                log.debug("No WebRTC session for local ufrag={} remote={}", localUfrag, packet.sender());
            }
            return;
        }
        byte[] response = iceBindingService.handleBindingRequest(session.iceAgent(), bytes, packet.sender());
        if (response == null) {
            return;
        }
        ctx.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(response), packet.sender()));
    }

    private String extractLocalUfrag(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        String normalized = username.trim();
        int separatorIndex = normalized.lastIndexOf(':');
        return separatorIndex >= 0 && separatorIndex < normalized.length() - 1
                ? normalized.substring(separatorIndex + 1)
                : normalized;
    }
}
