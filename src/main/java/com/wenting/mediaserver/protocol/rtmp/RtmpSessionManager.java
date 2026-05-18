package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory RTMP session registry.
 */
public final class RtmpSessionManager {

    private final Map<String, RtmpSession> sessionsById = new ConcurrentHashMap<String, RtmpSession>();

    public RtmpSession createSession() {
        RtmpSession session = new RtmpSession();
        sessionsById.put(session.sessionId(), session);
        return session;
    }

    public RtmpSession register(RtmpSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        sessionsById.put(session.sessionId(), session);
        return session;
    }

    public RtmpSession find(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionsById.get(sessionId);
    }

    public RtmpSession remove(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionsById.remove(sessionId);
    }

    public int count() {
        return sessionsById.size();
    }

    public Collection<RtmpSession> sessions() {
        return Collections.unmodifiableCollection(sessionsById.values());
    }

    public RtmpSession findByStreamKey(StreamKey streamKey) {
        if (streamKey == null) {
            return null;
        }
        for (RtmpSession session : sessionsById.values()) {
            if (session == null || !session.isPublisher() || session.streamKey() == null) {
                continue;
            }
            if (streamKey.equals(session.streamKey())) {
                return session;
            }
        }
        return null;
    }
}
