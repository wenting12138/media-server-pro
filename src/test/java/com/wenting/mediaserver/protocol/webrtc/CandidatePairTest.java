package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidatePair;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ICE candidate pair tests.
 */
public class CandidatePairTest {

    private final IceCandidate local = new IceCandidate("1", 1,
        new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST);
    private final IceCandidate remote = new IceCandidate("1", 1,
        new InetSocketAddress("10.0.0.1", 45000), CandidateType.HOST);

    @Test
    public void testPairCreation() {
        CandidatePair pair = new CandidatePair(local, remote);

        assertEquals(local, pair.getLocal());
        assertEquals(remote, pair.getRemote());
        assertEquals(CandidatePair.State.FROZEN, pair.getState());
        assertTrue("Pair priority should be positive", pair.getPriority() > 0);
    }

    @Test
    public void testPairStateTransition() {
        CandidatePair pair = new CandidatePair(local, remote);

        assertEquals(CandidatePair.State.FROZEN, pair.getState());
        pair.setState(CandidatePair.State.WAITING);
        assertEquals(CandidatePair.State.WAITING, pair.getState());
        pair.setState(CandidatePair.State.IN_PROGRESS);
        assertTrue("IN_PROGRESS should be active", pair.getState().isActive());
        pair.setState(CandidatePair.State.SUCCEEDED);
        assertEquals(CandidatePair.State.SUCCEEDED, pair.getState());
    }

    @Test
    public void testPairPriorityCalculation() {
        // priority: local = 2^24*126 + 2^8*65535 + 255 = 2118127871
        // remote = same formula with different address but same priority
        // Since both have same type and component
        // Pair priority = 2^32 * min + 2 * max + (local > remote ? 1 : 0)
        long localPrio = local.getPriority();
        long remotePrio = remote.getPriority();

        CandidatePair pair = new CandidatePair(local, remote);
        long expected = Math.min(localPrio, remotePrio) * (1L << 32)
            + 2 * Math.max(localPrio, remotePrio)
            + (localPrio > remotePrio ? 1 : 0);

        assertEquals(expected, pair.getPriority());
    }

    @Test
    public void testPairOrderingByPriority() {
        // Remote with higher priority should produce higher pair priority
        IceCandidate highPrioLocal = new IceCandidate("h1", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.HOST);
        IceCandidate lowPrioLocal = new IceCandidate("r1", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.RELAYED);

        List<CandidatePair> pairs = new ArrayList<>();
        pairs.add(new CandidatePair(lowPrioLocal, remote));
        pairs.add(new CandidatePair(highPrioLocal, remote));

        Collections.sort(pairs);

        assertTrue("HOST pair should be first (higher priority)",
            pairs.get(0).getLocal().getType() == CandidateType.HOST);
        assertTrue("RELAYED pair should be last",
            pairs.get(1).getLocal().getType() == CandidateType.RELAYED);
    }
}
