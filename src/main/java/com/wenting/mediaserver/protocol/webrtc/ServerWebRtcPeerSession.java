package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Service-layer wrapper around the generic WebRTC peer connection API.
 *
 * Keeps HTTP signaling and published-stream subscription state outside the
 * browser-style RTCPeerConnection object.
 */
public final class ServerWebRtcPeerSession implements AutoCloseable {

    private final String sessionId;
    private final StreamKey streamKey;
    private final RTCPeerConnection peerConnection;
    private final SessionDatagramIo datagramIo;

    public ServerWebRtcPeerSession(
            String sessionId,
            StreamKey streamKey,
            RTCPeerConnection peerConnection,
            SessionDatagramIo datagramIo
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.peerConnection = Objects.requireNonNull(peerConnection, "peerConnection");
        this.datagramIo = Objects.requireNonNull(datagramIo, "datagramIo");
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public RTCPeerConnection peerConnection() {
        return peerConnection;
    }

    public SessionDatagramIo datagramIo() {
        return datagramIo;
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        datagramIo.receive(data, remoteAddress);
    }

    @Override
    public void close() {
        peerConnection.close();
        datagramIo.close();
    }
}
