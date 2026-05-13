package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.stun.StunConstants;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Shared WebRTC UDP demux for server-side sessions.
 */
public final class WebRtcUdpPacketHandler implements UdpTransport.PacketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebRtcUdpPacketHandler.class);

    private final WebRtcSessionManager sessionManager;

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void onPacket(byte[] data, InetSocketAddress remoteAddress) {
        if (data == null || data.length == 0 || sessionManager == null) {
            return;
        }
        if (isStunPacket(data)) {
            handleStunPacket(data, remoteAddress);
            return;
        }
        ServerWebRtcPeerSession session = sessionManager.findByRemoteAddress(remoteAddress);
        if (session != null) {
            session.receive(data, remoteAddress);
        } else {
            log.debug("Dropping WebRTC UDP packet from {} because no session is bound", remoteAddress);
        }
    }

    private void handleStunPacket(byte[] data, InetSocketAddress remoteAddress) {
        try {
            StunMessage message = StunMessage.decode(data);
            String localUfrag = extractLocalUfrag(message);
            ServerWebRtcPeerSession session = sessionManager.findByLocalUfrag(localUfrag);
            if (session == null) {
                log.debug("Dropping STUN packet from {} because local ufrag {} is unknown", remoteAddress, localUfrag);
                return;
            }
            sessionManager.bindRemoteAddress(session, remoteAddress);
            session.receive(data, remoteAddress);
        } catch (Exception e) {
            log.debug("Failed to decode WebRTC STUN packet from {}: {}", remoteAddress, e.getMessage());
        }
    }

    private static boolean isStunPacket(byte[] data) {
        return data.length >= 20
                && data[4] == 0x21
                && data[5] == 0x12
                && data[6] == (byte) 0xA4
                && data[7] == 0x42;
    }

    private static String extractLocalUfrag(StunMessage message) {
        byte[] username = message.getAttributeValue(StunConstants.ATTR_USERNAME);
        if (username == null || username.length == 0) {
            return null;
        }
        String value = new String(username, StandardCharsets.UTF_8);
        int colonIndex = value.indexOf(':');
        if (colonIndex < 0 || colonIndex == value.length() - 1) {
            return value;
        }
        return value.substring(colonIndex + 1);
    }
}
