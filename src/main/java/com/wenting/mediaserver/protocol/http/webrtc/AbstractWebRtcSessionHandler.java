package com.wenting.mediaserver.protocol.http.webrtc;

import com.wenting.mediaserver.protocol.http.HttpRequestHandler;
import com.wenting.mediaserver.protocol.webrtc.WebRtcManagedSession;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;

/**
 * Shared teardown helpers for HTTP-created WebRTC peer sessions.
 */
abstract class AbstractWebRtcSessionHandler<S extends WebRtcManagedSession> implements HttpRequestHandler {

    protected final void cleanupFailedRequest(boolean success,
                                              S session,
                                              RTCPeerConnection peerConnection,
                                              SessionDatagramIo datagramIo,
                                              Runnable unmanagedCleanup) {
        if (success) {
            return;
        }
        if (session != null) {
            closeManagedSession(session);
            return;
        }
        if (unmanagedCleanup != null) {
            unmanagedCleanup.run();
        }
        peerConnection.close();
        datagramIo.close();
    }

    protected final void closeManagedSession(S session) {
        if (session == null) {
            return;
        }
        S removed = removeAndCloseManagedSession(session.sessionId());
        if (removed == null) {
            session.close();
        }
    }

    protected abstract S removeAndCloseManagedSession(String sessionId);
}
