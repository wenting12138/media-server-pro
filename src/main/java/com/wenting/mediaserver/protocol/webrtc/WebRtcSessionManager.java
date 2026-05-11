package com.wenting.mediaserver.protocol.webrtc;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebRtcSessionManager {

    private final Map<String, WebRtcPeerSession> sessionsById = new ConcurrentHashMap<String, WebRtcPeerSession>();

    public void register(WebRtcPeerSession session) {
        if (session == null || session.sessionId() == null || session.sessionId().trim().isEmpty()) {
            throw new IllegalArgumentException("session must not be null and must have a sessionId");
        }
        sessionsById.put(session.sessionId(), session);
    }

    public WebRtcPeerSession find(String sessionId) {
        return sessionId == null ? null : sessionsById.get(sessionId);
    }

    public WebRtcPeerSession findByLocalUfrag(String localUfrag) {
        if (localUfrag == null || localUfrag.trim().isEmpty()) {
            return null;
        }
        for (WebRtcPeerSession session : sessionsById.values()) {
            if (session != null && session.iceAgent() != null && localUfrag.equals(session.iceAgent().localUfrag())) {
                return session;
            }
        }
        return null;
    }

    public WebRtcPeerSession findByRemoteAddress(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        for (WebRtcPeerSession session : sessionsById.values()) {
            if (session != null && remoteAddress.equals(session.remoteAddress())) {
                return session;
            }
        }
        return null;
    }

    public WebRtcPeerSession remove(String sessionId) {
        return sessionId == null ? null : sessionsById.remove(sessionId);
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<WebRtcPeerSession> sessions() {
        return Collections.unmodifiableCollection(new LinkedHashMap<String, WebRtcPeerSession>(sessionsById).values());
    }
}
