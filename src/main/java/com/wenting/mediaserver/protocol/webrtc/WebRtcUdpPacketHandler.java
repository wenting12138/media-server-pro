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
    private final WebRtcPublishSessionManager publishSessionManager;

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager) {
        this(sessionManager, null);
    }

    public WebRtcUdpPacketHandler(WebRtcSessionManager sessionManager, WebRtcPublishSessionManager publishSessionManager) {
        this.sessionManager = sessionManager;
        this.publishSessionManager = publishSessionManager;
    }

    @Override
    public void onPacket(byte[] data, InetSocketAddress remoteAddress) {
        if (data == null || data.length == 0) {
            return;
        }
        if (isStunPacket(data)) {
            handleStunPacket(data, remoteAddress);
            return;
        }
        ServerWebRtcPeerSession playSession = sessionManager == null ? null : sessionManager.findByRemoteAddress(remoteAddress);
        if (playSession != null) {
            playSession.receive(data, remoteAddress);
            return;
        }
        WebRtcPublishPeerSession publishSession = publishSessionManager == null ? null : publishSessionManager.findByRemoteAddress(remoteAddress);
        if (publishSession != null) {
            publishSession.receive(data, remoteAddress);
        } else {
            log.debug("Dropping WebRTC UDP packet from {} because no session is bound", remoteAddress);
        }
    }

    private void handleStunPacket(byte[] data, InetSocketAddress remoteAddress) {
        try {
            StunMessage message = StunMessage.decode(data);
            if (bindAndForwardStunToPlaySession(message, data, remoteAddress)) {
                return;
            }
            if (bindAndForwardStunToPublishSession(message, data, remoteAddress)) {
                return;
            }
            log.debug("Dropping STUN packet from {} because username is unknown", remoteAddress);
        } catch (Exception e) {
            log.debug("Failed to decode WebRTC STUN packet from {}: {}", remoteAddress, e.getMessage());
        }
    }

    private boolean bindAndForwardStunToPlaySession(StunMessage message, byte[] data, InetSocketAddress remoteAddress) {
        if (sessionManager == null) {
            return false;
        }
        ServerWebRtcPeerSession session = findPlaySessionByUsername(message);
        if (session == null) {
            return false;
        }
        sessionManager.bindRemoteAddress(session, remoteAddress);
        session.receive(data, remoteAddress);
        return true;
    }

    private boolean bindAndForwardStunToPublishSession(StunMessage message, byte[] data, InetSocketAddress remoteAddress) {
        if (publishSessionManager == null) {
            return false;
        }
        WebRtcPublishPeerSession session = findPublishSessionByUsername(message);
        if (session == null) {
            return false;
        }
        publishSessionManager.bindRemoteAddress(session, remoteAddress);
        session.receive(data, remoteAddress);
        return true;
    }

    private ServerWebRtcPeerSession findPlaySessionByUsername(StunMessage message) {
        return sessionManager == null ? null : sessionManager.findByLocalUfrag(extractLocalUfrag(message));
    }

    private WebRtcPublishPeerSession findPublishSessionByUsername(StunMessage message) {
        return publishSessionManager == null ? null : publishSessionManager.findByLocalUfrag(extractLocalUfrag(message));
    }

    private String extractLocalUfrag(StunMessage message) {
        byte[] username = message.getAttributeValue(StunConstants.ATTR_USERNAME);
        if (username == null || username.length == 0) {
            return null;
        }
        String value = new String(username, StandardCharsets.UTF_8);
        ServerWebRtcPeerSession playSession = sessionManager == null ? null : sessionManager.findByLocalUfrag(value);
        if (playSession != null) {
            return value;
        }
        WebRtcPublishPeerSession publishSession = publishSessionManager == null ? null : publishSessionManager.findByLocalUfrag(value);
        if (publishSession != null) {
            return value;
        }
        int colonIndex = value.indexOf(':');
        if (colonIndex < 0) {
            return null;
        }
        String left = value.substring(0, colonIndex);
        if ((sessionManager != null && sessionManager.findByLocalUfrag(left) != null)
                || (publishSessionManager != null && publishSessionManager.findByLocalUfrag(left) != null)) {
            return left;
        }
        if (colonIndex < value.length() - 1) {
            String right = value.substring(colonIndex + 1);
            if ((sessionManager != null && sessionManager.findByLocalUfrag(right) != null)
                    || (publishSessionManager != null && publishSessionManager.findByLocalUfrag(right) != null)) {
                return right;
            }
        }
        return null;
    }

    private static boolean isStunPacket(byte[] data) {
        return data.length >= 20
                && data[4] == 0x21
                && data[5] == 0x12
                && data[6] == (byte) 0xA4
                && data[7] == 0x42;
    }

}
