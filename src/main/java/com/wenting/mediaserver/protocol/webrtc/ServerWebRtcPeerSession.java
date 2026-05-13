package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;

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

    public ServerWebRtcPeerSession(String sessionId, StreamKey streamKey, RTCPeerConnection peerConnection) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.peerConnection = Objects.requireNonNull(peerConnection, "peerConnection");
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

    @Override
    public void close() {
        peerConnection.close();
    }
}
