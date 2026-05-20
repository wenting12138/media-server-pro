package com.wenting.mediaserver.protocol.webrtc;

/**
 * Common lifecycle contract for server-managed WebRTC sessions.
 */
public interface WebRtcManagedSession extends AutoCloseable {

    String sessionId();

    @Override
    void close();
}
