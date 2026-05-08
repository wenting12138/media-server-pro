package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory RTSP session registry.
 */
public final class RtspSessionManager {

    private final Map<String, RtspSession> sessionsById = new ConcurrentHashMap<String, RtspSession>();

    public RtspSession findByStreamKey(StreamKey key) {
        if (key == null) {
            return null;
        }
        for (RtspSession session : sessionsById.values()) {
            if (session != null && key.equals(session.streamKey())) {
                return session;
            }
        }
        return null;
    }

    public RtspSession createSession() {
        RtspSession session = new RtspSession();
        sessionsById.put(session.sessionId(), session);
        return session;
    }

    public RtspSession register(RtspSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        sessionsById.put(session.sessionId(), session);
        return session;
    }

    public RtspSession find(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionsById.get(sessionId);
    }

    public RtspSession remove(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionsById.remove(sessionId);
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<RtspSession> sessions() {
        return Collections.unmodifiableCollection(sessionsById.values());
    }
}
