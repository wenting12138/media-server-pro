package com.wenting.mediaserver.protocol.webrtc;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory server-side WebRTC session index.
 */
public final class WebRtcPlaybackSessionManager implements AutoCloseable {

    private final Map<String, WebRtcPlaybackPeerSession> sessionsById = new ConcurrentHashMap<String, WebRtcPlaybackPeerSession>();
    private final Map<String, WebRtcPlaybackPeerSession> sessionsByLocalUfrag = new ConcurrentHashMap<String, WebRtcPlaybackPeerSession>();
    private final Map<InetSocketAddress, WebRtcPlaybackPeerSession> sessionsByRemoteAddress = new ConcurrentHashMap<InetSocketAddress, WebRtcPlaybackPeerSession>();

    public void register(WebRtcPlaybackPeerSession session) {
        if (session == null) {
            return;
        }
        sessionsById.put(session.sessionId(), session);
        String localUfrag = session.peerConnection().getLocalUfrag();
        if (localUfrag != null && !localUfrag.trim().isEmpty()) {
            sessionsByLocalUfrag.put(localUfrag, session);
        }
    }

    public WebRtcPlaybackPeerSession find(String sessionId) {
        return sessionId == null ? null : sessionsById.get(sessionId);
    }

    public WebRtcPlaybackPeerSession findByLocalUfrag(String localUfrag) {
        return localUfrag == null ? null : sessionsByLocalUfrag.get(localUfrag);
    }

    public WebRtcPlaybackPeerSession remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        WebRtcPlaybackPeerSession removed = sessionsById.remove(sessionId);
        if (removed != null) {
            String localUfrag = removed.peerConnection().getLocalUfrag();
            if (localUfrag != null) {
                sessionsByLocalUfrag.remove(localUfrag);
            }
            if (removed.peerConnection() != null) {
                for (Map.Entry<InetSocketAddress, WebRtcPlaybackPeerSession> entry : sessionsByRemoteAddress.entrySet()) {
                    if (entry != null && entry.getValue() == removed) {
                        sessionsByRemoteAddress.remove(entry.getKey());
                    }
                }
            }
        }
        return removed;
    }

    public WebRtcPlaybackPeerSession removeAndClose(String sessionId) {
        WebRtcPlaybackPeerSession removed = remove(sessionId);
        if (removed != null) {
            removed.close();
        }
        return removed;
    }

    public void bindRemoteAddress(WebRtcPlaybackPeerSession session, InetSocketAddress remoteAddress) {
        if (session == null || remoteAddress == null) {
            return;
        }
        sessionsByRemoteAddress.put(remoteAddress, session);
    }

    public WebRtcPlaybackPeerSession findByRemoteAddress(InetSocketAddress remoteAddress) {
        return remoteAddress == null ? null : sessionsByRemoteAddress.get(remoteAddress);
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<WebRtcPlaybackPeerSession> sessions() {
        return Collections.unmodifiableCollection(new LinkedHashMap<String, WebRtcPlaybackPeerSession>(sessionsById).values());
    }

    @Override
    public void close() {
        for (WebRtcPlaybackPeerSession session : new LinkedHashMap<String, WebRtcPlaybackPeerSession>(sessionsById).values()) {
            if (session != null) {
                removeAndClose(session.sessionId());
            }
        }
    }
}
