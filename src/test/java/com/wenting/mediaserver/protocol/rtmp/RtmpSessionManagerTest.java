package com.wenting.mediaserver.protocol.rtmp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RtmpSessionManagerTest {

    @Test
    void shouldCreateFindAndRemoveSession() {
        RtmpSessionManager manager = new RtmpSessionManager();

        RtmpSession session = manager.createSession();

        assertNotNull(session);
        assertEquals(1, manager.count());
        assertSame(session, manager.find(session.sessionId()));
        assertSame(session, manager.remove(session.sessionId()));
        assertEquals(0, manager.count());
        assertNull(manager.find(session.sessionId()));
    }
}
