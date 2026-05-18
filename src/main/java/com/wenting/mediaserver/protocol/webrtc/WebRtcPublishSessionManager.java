package com.wenting.mediaserver.protocol.webrtc;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory server-side WebRTC publish session index.
 */
public final class WebRtcPublishSessionManager implements AutoCloseable {

    private final Map<String, WebRtcPublishPeerSession> sessionsById = new ConcurrentHashMap<String, WebRtcPublishPeerSession>();
    private final Map<String, WebRtcPublishPeerSession> sessionsByLocalUfrag = new ConcurrentHashMap<String, WebRtcPublishPeerSession>();
    private final Map<InetSocketAddress, WebRtcPublishPeerSession> sessionsByRemoteAddress = new ConcurrentHashMap<InetSocketAddress, WebRtcPublishPeerSession>();

    public void register(WebRtcPublishPeerSession session) {
        if (session == null) {
            return;
        }
        sessionsById.put(session.sessionId(), session);
        String localUfrag = session.peerConnection().getLocalUfrag();
        if (localUfrag != null && !localUfrag.trim().isEmpty()) {
            sessionsByLocalUfrag.put(localUfrag, session);
        }
    }

    public WebRtcPublishPeerSession find(String sessionId) {
        return sessionId == null ? null : sessionsById.get(sessionId);
    }

    public WebRtcPublishPeerSession findByLocalUfrag(String localUfrag) {
        return localUfrag == null ? null : sessionsByLocalUfrag.get(localUfrag);
    }

    public void bindRemoteAddress(WebRtcPublishPeerSession session, InetSocketAddress remoteAddress) {
        if (session == null || remoteAddress == null) {
            return;
        }
        sessionsByRemoteAddress.put(remoteAddress, session);
    }

    public WebRtcPublishPeerSession findByRemoteAddress(InetSocketAddress remoteAddress) {
        return remoteAddress == null ? null : sessionsByRemoteAddress.get(remoteAddress);
    }

    public WebRtcPublishPeerSession remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        WebRtcPublishPeerSession removed = sessionsById.remove(sessionId);
        if (removed != null) {
            String localUfrag = removed.peerConnection().getLocalUfrag();
            if (localUfrag != null) {
                sessionsByLocalUfrag.remove(localUfrag);
            }
            for (Map.Entry<InetSocketAddress, WebRtcPublishPeerSession> entry : sessionsByRemoteAddress.entrySet()) {
                if (entry != null && entry.getValue() == removed) {
                    sessionsByRemoteAddress.remove(entry.getKey());
                }
            }
        }
        return removed;
    }

    public WebRtcPublishPeerSession removeAndClose(String sessionId) {
        WebRtcPublishPeerSession removed = remove(sessionId);
        if (removed != null) {
            removed.close();
        }
        return removed;
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<WebRtcPublishPeerSession> sessions() {
        return Collections.unmodifiableCollection(new LinkedHashMap<String, WebRtcPublishPeerSession>(sessionsById).values());
    }

    @Override
    public void close() {
        for (WebRtcPublishPeerSession session : new LinkedHashMap<String, WebRtcPublishPeerSession>(sessionsById).values()) {
            if (session != null) {
                removeAndClose(session.sessionId());
            }
        }
    }
}
