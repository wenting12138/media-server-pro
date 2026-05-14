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
public final class WebRtcSessionManager implements AutoCloseable {

    private final Map<String, ServerWebRtcPeerSession> sessionsById = new ConcurrentHashMap<String, ServerWebRtcPeerSession>();
    private final Map<String, ServerWebRtcPeerSession> sessionsByLocalUfrag = new ConcurrentHashMap<String, ServerWebRtcPeerSession>();
    private final Map<InetSocketAddress, ServerWebRtcPeerSession> sessionsByRemoteAddress = new ConcurrentHashMap<InetSocketAddress, ServerWebRtcPeerSession>();

    public void register(ServerWebRtcPeerSession session) {
        if (session == null) {
            return;
        }
        sessionsById.put(session.sessionId(), session);
        String localUfrag = session.peerConnection().getLocalUfrag();
        if (localUfrag != null && !localUfrag.trim().isEmpty()) {
            sessionsByLocalUfrag.put(localUfrag, session);
        }
    }

    public ServerWebRtcPeerSession find(String sessionId) {
        return sessionId == null ? null : sessionsById.get(sessionId);
    }

    public ServerWebRtcPeerSession findByLocalUfrag(String localUfrag) {
        return localUfrag == null ? null : sessionsByLocalUfrag.get(localUfrag);
    }

    public ServerWebRtcPeerSession remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        ServerWebRtcPeerSession removed = sessionsById.remove(sessionId);
        if (removed != null) {
            String localUfrag = removed.peerConnection().getLocalUfrag();
            if (localUfrag != null) {
                sessionsByLocalUfrag.remove(localUfrag);
            }
            if (removed.peerConnection() != null) {
                for (Map.Entry<InetSocketAddress, ServerWebRtcPeerSession> entry : sessionsByRemoteAddress.entrySet()) {
                    if (entry != null && entry.getValue() == removed) {
                        sessionsByRemoteAddress.remove(entry.getKey());
                    }
                }
            }
        }
        return removed;
    }

    public ServerWebRtcPeerSession removeAndClose(String sessionId) {
        ServerWebRtcPeerSession removed = remove(sessionId);
        if (removed != null) {
            removed.close();
        }
        return removed;
    }

    public void bindRemoteAddress(ServerWebRtcPeerSession session, InetSocketAddress remoteAddress) {
        if (session == null || remoteAddress == null) {
            return;
        }
        sessionsByRemoteAddress.put(remoteAddress, session);
    }

    public ServerWebRtcPeerSession findByRemoteAddress(InetSocketAddress remoteAddress) {
        return remoteAddress == null ? null : sessionsByRemoteAddress.get(remoteAddress);
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<ServerWebRtcPeerSession> sessions() {
        return Collections.unmodifiableCollection(new LinkedHashMap<String, ServerWebRtcPeerSession>(sessionsById).values());
    }

    @Override
    public void close() {
        for (ServerWebRtcPeerSession session : new LinkedHashMap<String, ServerWebRtcPeerSession>(sessionsById).values()) {
            if (session != null) {
                removeAndClose(session.sessionId());
            }
        }
    }
}
