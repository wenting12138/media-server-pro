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

public final class WebRtcUdpPacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger log = LoggerFactory.getLogger(WebRtcUdpPacketHandler.class);

    private final WebRtcSessionManager sessionManager;
    private final WebRtcDatagramSender datagramSender;
    private final IceBindingService iceBindingService = new IceBindingService();
    private final StunMessageCodec stunMessageCodec = new StunMessageCodec();

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager) {
        this(sessionManager, null);
    }

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager, WebRtcDatagramSender datagramSender) {
        this.sessionManager = sessionManager;
        this.datagramSender = datagramSender;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        byte[] bytes = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), bytes);
        StunMessage message = stunMessageCodec.decode(bytes);
        if (message == null) {
            handleDtlsPacket(ctx, packet, bytes);
            return;
        }
        if (message.type() != StunMessageType.BINDING_REQUEST) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring unsupported STUN message type {} from {}", message.type(), packet.sender());
            }
            return;
        }
        String localUfrag = extractLocalUfrag(message.username());
        log.info("Received STUN binding request from {} local ufrag={}", packet.sender(), localUfrag);
        WebRtcPeerSession session = sessionManager.findByLocalUfrag(localUfrag);
        if (session == null || session.iceAgent() == null) {
            if (log.isDebugEnabled()) {
                log.debug("No WebRTC session for local ufrag={} remote={}", localUfrag, packet.sender());
            }
            return;
        }
        session.remoteAddress(packet.sender());
        byte[] response = iceBindingService.handleBindingRequest(session.iceAgent(), bytes, packet.sender());
        if (response == null) {
            return;
        }
        if (datagramSender != null) {
            datagramSender.send(response, packet.sender());
        } else {
            ctx.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(response), packet.sender()));
        }
    }

    private void handleDtlsPacket(ChannelHandlerContext ctx, DatagramPacket packet, byte[] bytes) {
        WebRtcPeerSession session = sessionManager.findByRemoteAddress(packet.sender());
        if (session == null || session.dtlsServerTransport() == null) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring non-STUN WebRTC UDP packet from {}", packet.sender());
            }
            return;
        }
        byte[] serverHelloFlight = session.dtlsServerTransport().handleClientHello(bytes, packet.sender());
        if (serverHelloFlight != null) {
            session.dtlsServerTransport().markServerHelloSent();
            log.info(
                    "Received DTLS ClientHello session={} remote={} state={}",
                    session.sessionId(),
                    packet.sender(),
                    session.dtlsServerTransport().state()
            );
            if (serverHelloFlight.length > 0) {
                if (datagramSender != null) {
                    datagramSender.send(serverHelloFlight, packet.sender());
                } else {
                    ctx.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(serverHelloFlight), packet.sender()));
                }
            }
            return;
        }
        if (session.dtlsServerTransport().handleClientFlight(bytes, packet.sender())) {
            log.info(
                    "Received DTLS client flight session={} remote={} state={}",
                    session.sessionId(),
                    packet.sender(),
                    session.dtlsServerTransport().state()
            );
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Ignoring unsupported DTLS packet session={} remote={}", session.sessionId(), packet.sender());
        }
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
