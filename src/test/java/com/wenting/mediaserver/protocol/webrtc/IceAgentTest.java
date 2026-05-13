package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidatePair;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent.State;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ICE agent unit tests.
 */
public class IceAgentTest {

    @Test
    public void testInitialState() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        assertEquals(State.NEW, agent.getState());
        assertEquals(IceAgent.Role.CONTROLLING, agent.getRole());
    }

    @Test
    public void testAddLocalCandidates() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);

        List<IceCandidate> candidates = new ArrayList<>();
        candidates.add(new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST));
        candidates.add(new IceCandidate("2", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.HOST));

        agent.addLocalCandidates(candidates);
        assertEquals(2, agent.getLocalCandidates().size());
    }

    @Test
    public void testBuildCheckList() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);

        List<IceCandidate> locals = new ArrayList<>();
        locals.add(new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST));
        agent.addLocalCandidates(locals);

        List<IceCandidate> remotes = new ArrayList<>();
        remotes.add(new IceCandidate("1", 1,
            new InetSocketAddress("10.0.0.1", 45000), CandidateType.HOST));
        agent.setRemoteCandidates(remotes);

        assertEquals("Should have 1 pair (1 local x 1 remote)", 1, agent.getCheckList().size());
    }

    @Test
    public void testMultipleCandidatePairs() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);

        List<IceCandidate> locals = new ArrayList<>();
        locals.add(new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST));
        locals.add(new IceCandidate("2", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.HOST));
        agent.addLocalCandidates(locals);

        List<IceCandidate> remotes = new ArrayList<>();
        remotes.add(new IceCandidate("1", 1,
            new InetSocketAddress("10.0.0.2", 45000), CandidateType.HOST));
        remotes.add(new IceCandidate("2", 1,
            new InetSocketAddress("192.168.2.1", 45000), CandidateType.HOST));
        agent.setRemoteCandidates(remotes);

        assertEquals("Should have 4 pairs", 4, agent.getCheckList().size());
    }

    @Test
    public void testStartWithoutCandidatesFails() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        agent.startConnectivityChecks();
        assertEquals("Should fail with no candidates", State.FAILED, agent.getState());
    }

    @Test
    public void testCheckListSortedByPriority() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);

        List<IceCandidate> locals = new ArrayList<>();
        locals.add(new IceCandidate("h1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST));
        locals.add(new IceCandidate("r1", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.RELAYED));
        agent.addLocalCandidates(locals);

        List<IceCandidate> remotes = new ArrayList<>();
        remotes.add(new IceCandidate("h1", 1,
            new InetSocketAddress("10.0.0.2", 45000), CandidateType.HOST));
        remotes.add(new IceCandidate("r1", 1,
            new InetSocketAddress("10.0.0.2", 45000), CandidateType.RELAYED));
        agent.setRemoteCandidates(remotes);

        List<CandidatePair> checkList = agent.getCheckList();

        assertTrue("First pair should involve HOST candidate",
            checkList.get(0).getLocal().getType() == CandidateType.HOST);
        assertTrue("Last pair should involve RELAYED candidate",
            checkList.get(checkList.size() - 1).getLocal().getType() == CandidateType.RELAYED);
    }

    @Test
    public void testRoleControlled() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLED);
        assertEquals(IceAgent.Role.CONTROLLED, agent.getRole());
    }

    @Test
    public void testGatheringStateNoStunServers() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        agent.addLocalCandidates(new ArrayList<IceCandidate>() {{
            add(new IceCandidate("1", 1,
                new InetSocketAddress("127.0.0.1", 5000), CandidateType.HOST));
        }});

        agent.startGathering();
        assertTrue("Gathering should complete immediately with no STUN servers",
            agent.isGatheringComplete());
    }

    @Test
    public void testGatheringStateEmptyCandidatesFails() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        agent.startGathering();
        assertEquals("Should fail with no candidates", State.FAILED, agent.getState());
    }

    @Test
    public void testRestartIceResetsState() {
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        agent.addLocalCandidates(new ArrayList<IceCandidate>() {{
            add(new IceCandidate("1", 1,
                new InetSocketAddress("127.0.0.1", 5000), CandidateType.HOST));
        }});

        agent.startGathering();
        assertTrue(agent.isGatheringComplete());

        String[] creds = agent.restartIce();
        assertNotNull("Should return credentials", creds);
        assertEquals("Should have ufrag", 2, creds.length);
        assertTrue("ufrag should be non-empty", creds[0].length() > 0);
        assertTrue("pwd should be non-empty", creds[1].length() > 0);
        assertEquals("State should be NEW after restart", State.NEW, agent.getState());
        assertTrue("Local candidates should be cleared", agent.getLocalCandidates().isEmpty());
        assertFalse("Gathering should not be complete", agent.isGatheringComplete());
    }

    @Test
    public void testSrflxCandidateNotCreatedForLocalBindingResponse() {
        // Verify that handleStunMessage doesn't crash on Binding Response
        // (srflx path requires a pending transaction which won't exist here)
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);

        byte[] txId = new byte[12];
        new java.security.SecureRandom().nextBytes(txId);
        StunMessage resp = StunMessage.createBindingResponse(txId,
            new InetSocketAddress("127.0.0.1", 3478), null);

        // This should not throw
        agent.handleStunMessage(resp, new InetSocketAddress("127.0.0.1", 3478));
    }

    @Test
    public void testDuplicateStunResponse() {
        // Verify that repeated STUN responses don't break anything
        IceAgent agent = new IceAgent(null, IceAgent.Role.CONTROLLING);
        agent.addLocalCandidates(new ArrayList<IceCandidate>() {{
            add(new IceCandidate("1", 1,
                new InetSocketAddress("127.0.0.1", 5000), CandidateType.HOST));
        }});

        byte[] txId = new byte[12];
        new java.security.SecureRandom().nextBytes(txId);
        StunMessage resp = StunMessage.createBindingResponse(txId,
            new InetSocketAddress("127.0.0.1", 5000), null);

        // Should not throw, just ignore unmatched transactions
        agent.handleStunMessage(resp, new InetSocketAddress("127.0.0.1", 5000));
        agent.handleStunMessage(resp, new InetSocketAddress("127.0.0.1", 5000));
    }
}
