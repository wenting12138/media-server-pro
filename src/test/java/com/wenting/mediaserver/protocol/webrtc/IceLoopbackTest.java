package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidatePair;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceEvent;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Loopback ICE integration test between two local agents.
 */
public class IceLoopbackTest {

    private UdpTransport transportA;
    private UdpTransport transportB;
    private IceAgent agentA;
    private IceAgent agentB;

    @Before
    public void setUp() throws Exception {
        transportA = new UdpTransport(0);
        transportB = new UdpTransport(0);
        transportA.start();
        transportB.start();
    }

    @After
    public void tearDown() {
        if (agentA != null) agentA.shutdown();
        if (agentB != null) agentB.shutdown();
        transportB.close();
        transportA.close();
    }

    private void setupTransportHandlers() {
        transportA.setPacketHandler((data, remote) -> {
            try {
                StunMessage msg =
                    StunMessage.decode(data);
                agentA.handleStunMessage(msg, remote);
            } catch (Exception e) {
                // Ignore non-STUN packets
            }
        });
        transportB.setPacketHandler((data, remote) -> {
            try {
                StunMessage msg =
                    StunMessage.decode(data);
                agentB.handleStunMessage(msg, remote);
            } catch (Exception e) {
                // Ignore non-STUN packets
            }
        });
    }

    @Test
    public void testLoopbackIceHandshake() throws Exception {
        int portA = transportA.getLocalAddress().getPort();
        int portB = transportB.getLocalAddress().getPort();

        agentA = new IceAgent(transportA, IceAgent.Role.CONTROLLING);
        agentB = new IceAgent(transportB, IceAgent.Role.CONTROLLED);
        setupTransportHandlers();

        List<IceCandidate> localsA = new ArrayList<>();
        localsA.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portA), CandidateType.HOST));
        agentA.addLocalCandidates(localsA);

        List<IceCandidate> localsB = new ArrayList<>();
        localsB.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portB), CandidateType.HOST));
        agentB.addLocalCandidates(localsB);

        agentA.setRemoteCandidates(localsB);
        agentB.setRemoteCandidates(localsA);

        CountDownLatch latch = new CountDownLatch(2);

        agentA.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.PAIR_SUCCEEDED) {
                latch.countDown();
            }
        });
        agentB.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.PAIR_SUCCEEDED) {
                latch.countDown();
            }
        });

        agentA.startConnectivityChecks();
        agentB.startConnectivityChecks();

        boolean bothDone = latch.await(5, TimeUnit.SECONDS);

        assertTrue("ICE loopback should complete within 5 seconds", bothDone);
        assertEquals(IceAgent.State.CONNECTED, agentA.getState());
        assertEquals(IceAgent.State.CONNECTED, agentB.getState());
        assertNotNull("Agent A should have selected pair", agentA.getSelectedPair());
        assertNotNull("Agent B should have selected pair", agentB.getSelectedPair());
    }

    @Test
    public void testLoopbackIceNomination() throws Exception {
        int portA = transportA.getLocalAddress().getPort();
        int portB = transportB.getLocalAddress().getPort();

        agentA = new IceAgent(transportA, IceAgent.Role.CONTROLLING);
        agentB = new IceAgent(transportB, IceAgent.Role.CONTROLLED);
        setupTransportHandlers();

        List<IceCandidate> localsA = new ArrayList<>();
        localsA.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portA), CandidateType.HOST));
        agentA.addLocalCandidates(localsA);

        List<IceCandidate> localsB = new ArrayList<>();
        localsB.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portB), CandidateType.HOST));
        agentB.addLocalCandidates(localsB);

        agentA.setRemoteCandidates(localsB);
        agentB.setRemoteCandidates(localsA);

        CountDownLatch completedLatch = new CountDownLatch(2);

        agentA.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.STATE_CHANGED
                && agentA.getState() == IceAgent.State.COMPLETED) {
                completedLatch.countDown();
            }
        });
        agentB.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.STATE_CHANGED
                && agentB.getState() == IceAgent.State.COMPLETED) {
                completedLatch.countDown();
            }
        });

        agentA.startConnectivityChecks();
        agentB.startConnectivityChecks();

        boolean bothCompleted = completedLatch.await(5, TimeUnit.SECONDS);
        assertTrue("Both agents should reach COMPLETED within 5s", bothCompleted);
        assertEquals(IceAgent.State.COMPLETED, agentA.getState());
        assertEquals(IceAgent.State.COMPLETED, agentB.getState());
        assertNotNull("Agent A should have selected pair", agentA.getSelectedPair());
        assertEquals("Selected pair should be NOMINATED",
            CandidatePair.State.NOMINATED, agentA.getSelectedPair().getState());
    }

    @Test(timeout = 15000)
    public void testLoopbackIceRestart() throws Exception {
        // Initial connection
        int portA = transportA.getLocalAddress().getPort();
        int portB = transportB.getLocalAddress().getPort();

        agentA = new IceAgent(transportA, IceAgent.Role.CONTROLLING);
        agentB = new IceAgent(transportB, IceAgent.Role.CONTROLLED);
        setupTransportHandlers();

        List<IceCandidate> localsA = new ArrayList<>();
        localsA.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portA), CandidateType.HOST));
        agentA.addLocalCandidates(localsA);

        List<IceCandidate> localsB = new ArrayList<>();
        localsB.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portB), CandidateType.HOST));
        agentB.addLocalCandidates(localsB);

        agentA.setRemoteCandidates(localsB);
        agentB.setRemoteCandidates(localsA);

        CountDownLatch firstConnect = new CountDownLatch(1);
        agentB.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.STATE_CHANGED
                && (agentB.getState() == IceAgent.State.CONNECTED
                    || agentB.getState() == IceAgent.State.COMPLETED)) {
                firstConnect.countDown();
            }
        });

        agentA.startConnectivityChecks();
        agentB.startConnectivityChecks();
        assertTrue("First connection should complete", firstConnect.await(5, TimeUnit.SECONDS));

        // Restart ICE on both agents
        agentA.restartIce();
        agentB.restartIce();

        // After restart, re-setup with same ports
        // (In a real scenario, new ports/credentials would be exchanged via signaling)
        localsA = new ArrayList<>();
        localsA.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portA), CandidateType.HOST));
        agentA.addLocalCandidates(localsA);

        localsB = new ArrayList<>();
        localsB.add(new IceCandidate("1", 1,
            new InetSocketAddress("127.0.0.1", portB), CandidateType.HOST));
        agentB.addLocalCandidates(localsB);

        agentA.setRemoteCandidates(localsB);
        agentB.setRemoteCandidates(localsA);

        CountDownLatch reconnect = new CountDownLatch(1);
        agentB.addEventListener(event -> {
            if (event.getType() == IceEvent.Type.STATE_CHANGED
                && agentB.getState() == IceAgent.State.COMPLETED) {
                reconnect.countDown();
            }
        });

        agentA.startConnectivityChecks();
        agentB.startConnectivityChecks();

        assertTrue("Should reconnect after restart", reconnect.await(5, TimeUnit.SECONDS));
        assertEquals(IceAgent.State.COMPLETED, agentB.getState());
    }
}
