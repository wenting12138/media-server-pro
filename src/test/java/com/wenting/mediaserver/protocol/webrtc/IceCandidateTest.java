package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateGatherer;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ICE candidate model tests.
 */
public class IceCandidateTest {

    @Test
    public void testHostCandidatePriority() {
        IceCandidate c = new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST);
        // type_pref=126, local_pref=65535, component=1
        // priority = 2^24 * 126 + 2^8 * 65535 + (256-1)
        long expected = (1L << 24) * 126 + (1L << 8) * 65535 + 255;
        assertEquals("Host candidate priority", expected, c.getPriority());
    }

    @Test
    public void testCandidateOrderingByType() {
        List<IceCandidate> candidates = new ArrayList<>();
        candidates.add(new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST));
        candidates.add(new IceCandidate("2", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.SERVER_REFLEXIVE));
        candidates.add(new IceCandidate("3", 1,
            new InetSocketAddress("203.0.113.1", 5000), CandidateType.RELAYED));

        Collections.sort(candidates);

        assertEquals("HOST should have highest priority",
            CandidateType.HOST, candidates.get(0).getType());
        assertEquals("SRFLX should be second",
            CandidateType.SERVER_REFLEXIVE, candidates.get(1).getType());
        assertEquals("RELAYED should be lowest",
            CandidateType.RELAYED, candidates.get(2).getType());
    }

    @Test
    public void testSdpAttributeFormat() {
        IceCandidate c = new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.100", 5000), CandidateType.HOST);

        String sdp = c.toSdpAttribute();

        assertTrue("Should start with a=candidate:", sdp.startsWith("a=candidate:"));
        assertTrue("Should contain typ host", sdp.contains("typ host"));
        assertTrue("Should contain the address", sdp.contains("192.168.1.100"));
        assertTrue("Should contain the port", sdp.contains("5000"));
    }

    @Test
    public void testCandidateEquality() {
        IceCandidate a = new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST);
        IceCandidate b = new IceCandidate("1", 1,
            new InetSocketAddress("192.168.1.1", 5000), CandidateType.HOST);
        IceCandidate c = new IceCandidate("2", 1,
            new InetSocketAddress("10.0.0.1", 5000), CandidateType.HOST);

        assertEquals("Same candidates should be equal", a, b);
        assertEquals("Same hash", a.hashCode(), b.hashCode());
        assertNotEquals("Different candidates should differ", a, c);
    }

    @Test
    public void testCandidateGatherer() throws Exception {
        CandidateGatherer gatherer = new CandidateGatherer(5000);
        List<IceCandidate> candidates = gatherer.gather();

        assertNotNull("Should not return null", candidates);
        // May be empty on machines without network, or return some candidates
        for (IceCandidate c : candidates) {
            assertEquals("Should be HOST type", CandidateType.HOST, c.getType());
            assertEquals("Should have component 1", 1, c.getComponentId());
            assertEquals("Transport should be UDP", "UDP", c.getTransport());
        }
    }

    @Test
    public void testGathererHostCandidates() throws Exception {
        CandidateGatherer gatherer = new CandidateGatherer(5000);
        List<IceCandidate> candidates = gatherer.gatherHostCandidates();

        assertNotNull("Should not return null", candidates);
        // May contain both IPv4 and IPv6 candidates
        for (IceCandidate c : candidates) {
            assertEquals("Should be HOST type", CandidateType.HOST, c.getType());
            assertEquals("Transport should be UDP", "UDP", c.getTransport());
        }
    }

    @Test
    public void testGathererFoundationFormat() throws Exception {
        CandidateGatherer gatherer = new CandidateGatherer(5000);
        List<IceCandidate> candidates = gatherer.gatherHostCandidates();

        for (IceCandidate c : candidates) {
            String foundation = c.getFoundation();
            // Foundation should be like "eth0-host-v4" or "wlan0-host-v6"
            assertTrue("Foundation should contain '-host-'",
                foundation.contains("-host-"));
            assertTrue("Foundation should end with -v4 or -v6",
                foundation.endsWith("-v4") || foundation.endsWith("-v6"));
        }
    }

    @Test
    public void testTypeConversion() {
        assertEquals("host", IceCandidate.typeToString(CandidateType.HOST));
        assertEquals("srflx", IceCandidate.typeToString(CandidateType.SERVER_REFLEXIVE));
        assertEquals("prflx", IceCandidate.typeToString(CandidateType.PEER_REFLEXIVE));
        assertEquals("relay", IceCandidate.typeToString(CandidateType.RELAYED));

        assertEquals(CandidateType.HOST, IceCandidate.stringToType("host"));
        assertEquals(CandidateType.SERVER_REFLEXIVE, IceCandidate.stringToType("srflx"));
        assertEquals(CandidateType.PEER_REFLEXIVE, IceCandidate.stringToType("prflx"));
        assertEquals(CandidateType.RELAYED, IceCandidate.stringToType("relay"));
    }
}
