package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.sctp.*;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;
import static org.junit.Assert.*;

/**
 * SCTP association state machine tests (RFC 4960).
 */
public class SctpAssociationTest {

    @Test
    public void testClientConnectReturnsInit() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        assertEquals(SctpAssociation.State.CLOSED, client.getState());

        List<byte[]> packets = client.connect();
        assertEquals(1, packets.size());
        assertEquals(SctpAssociation.State.COOKIE_WAIT, client.getState());

        // Verify it's an INIT chunk inside an SCTP packet
        SctpPacket pkt = SctpPacket.decode(packets.get(0));
        assertEquals(1, pkt.getChunks().size());
        assertTrue(pkt.getChunks().get(0) instanceof SctpChunk.Init);
    }

    @Test
    public void testServerRejectsConnect() {
        SctpAssociation server = new SctpAssociation(5000, 5000, false);
        List<byte[]> packets = server.connect();
        assertTrue(packets.isEmpty());
        assertEquals(SctpAssociation.State.CLOSED, server.getState());
    }

    @Test
    public void testFullHandshake() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        // Client sends INIT
        List<byte[]> out = client.connect();
        assertEquals(SctpAssociation.State.COOKIE_WAIT, client.getState());

        // Server receives INIT → INIT-ACK
        out = server.onPacket(out.get(0));
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(SctpAssociation.State.COOKIE_ECHOED, server.getState());

        // Client receives INIT-ACK → COOKIE-ECHO
        out = client.onPacket(out.get(0));
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(SctpAssociation.State.COOKIE_ECHOED, client.getState());

        // Server receives COOKIE-ECHO → COOKIE-ACK
        out = server.onPacket(out.get(0));
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(SctpAssociation.State.ESTABLISHED, server.getState());

        // Client receives COOKIE-ACK → ESTABLISHED
        out = client.onPacket(out.get(0));
        assertEquals(SctpAssociation.State.ESTABLISHED, client.getState());
    }

    @Test
    public void testDataExchange() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        // Complete handshake
        List<byte[]> out = client.connect();
        out = server.onPacket(out.get(0));
        out = client.onPacket(out.get(0));
        out = server.onPacket(out.get(0));
        client.onPacket(out.get(0));
        assertEstablished(client, server);

        // Set up server data handler
        AtomicReference<String> received = new AtomicReference<>();
        server.setDataHandler((streamId, ppid, data, unordered) ->
            received.set(new String(data, java.nio.charset.StandardCharsets.UTF_8)));

        // Client sends data
        byte[] dataPacket = client.createDataPacket(0, PPID_WEBRTC_STRING, "Hello".getBytes(), true);
        assertNotNull(dataPacket);

        // Server receives data
        List<byte[]> serverResp = server.onPacket(dataPacket);
        assertEquals("Hello", received.get());

        // Server should send a SACK
        assertNotNull(serverResp);
        assertTrue(serverResp.size() >= 1);
        SctpPacket sackPkt = SctpPacket.decode(serverResp.get(0));
        assertTrue(sackPkt.getChunks().get(0) instanceof SctpChunk.Sack);

        // Client receives SACK — no response expected
        List<byte[]> clientResp = client.onPacket(serverResp.get(0));
        assertNotNull(clientResp);
        assertTrue(clientResp.isEmpty());
    }

    @Test
    public void testBidirectionalData() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        // Handshake
        List<byte[]> out = client.connect();
        out = server.onPacket(out.get(0));
        out = client.onPacket(out.get(0));
        out = server.onPacket(out.get(0));
        client.onPacket(out.get(0));
        assertEstablished(client, server);

        // Set handlers
        AtomicReference<String> clientReceived = new AtomicReference<>();
        AtomicReference<String> serverReceived = new AtomicReference<>();
        client.setDataHandler((sid, ppid, data, unordered) ->
            clientReceived.set(new String(data, java.nio.charset.StandardCharsets.UTF_8)));
        server.setDataHandler((sid, ppid, data, unordered) ->
            serverReceived.set(new String(data, java.nio.charset.StandardCharsets.UTF_8)));

        // Client → Server
        byte[] dp = client.createDataPacket(0, PPID_WEBRTC_STRING, "from client".getBytes(), false);
        out = server.onPacket(dp);
        assertEquals("from client", serverReceived.get());

        // Server → Client (send data + SACK from previous step)
        // The SACK is in out[0], now create actual data
        byte[] serverData = server.createDataPacket(0, PPID_WEBRTC_STRING, "from server".getBytes(), false);
        List<byte[]> clientResp = client.onPacket(serverData);
        assertEquals("from server", clientReceived.get());
        assertNotNull(clientResp); // Client should SACK too
    }

    @Test
    public void testRetransmitUnacknowledgedData() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);

        // No data yet → empty retransmit
        assertTrue(client.retransmit().isEmpty());

        // Create data (adds to unacknowledged map)
        client.createDataPacket(0, PPID_WEBRTC_STRING, "data".getBytes(), false);

        List<byte[]> retransmits = client.retransmit();
        assertEquals(1, retransmits.size());

        // Verify it decodes as a DATA chunk
        SctpPacket pkt = SctpPacket.decode(retransmits.get(0));
        assertEquals(1, pkt.getChunks().size());
        assertTrue(pkt.getChunks().get(0) instanceof SctpChunk.Data);
    }

    @Test
    public void testCleanupRetransmits() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        client.createDataPacket(0, PPID_WEBRTC_STRING, "data".getBytes(), false);
        assertEquals(1, client.retransmit().size());

        // Cleanup with maxRetrans=0 → all data should be removed
        client.cleanupRetransmits(0);
        assertTrue(client.retransmit().isEmpty());
    }

    @Test
    public void testSackRemovesUnacknowledged() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        // Handshake
        List<byte[]> out = client.connect();
        out = server.onPacket(out.get(0));
        out = client.onPacket(out.get(0));
        out = server.onPacket(out.get(0));
        client.onPacket(out.get(0));
        assertEstablished(client, server);

        // Client sends data → tracked in unacknowledged
        byte[] dataPkt = client.createDataPacket(0, PPID_WEBRTC_STRING, "test".getBytes(), false);
        assertEquals(1, client.retransmit().size());

        // Server receives → sends SACK
        List<byte[]> serverResp = server.onPacket(dataPkt);

        // Client receives SACK → should clear unacknowledged
        client.onPacket(serverResp.get(0));
        assertTrue(client.retransmit().isEmpty());
    }

    @Test
    public void testStateChangeCallback() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        AtomicReference<SctpAssociation.State> clientState = new AtomicReference<>();
        AtomicReference<SctpAssociation.State> serverState = new AtomicReference<>();
        client.setStateHandler(clientState::set);
        server.setStateHandler(serverState::set);

        List<byte[]> out = client.connect();
        assertEquals(SctpAssociation.State.COOKIE_WAIT, clientState.get());

        out = server.onPacket(out.get(0));
        assertEquals(SctpAssociation.State.COOKIE_ECHOED, serverState.get());

        out = client.onPacket(out.get(0));
        assertEquals(SctpAssociation.State.COOKIE_ECHOED, clientState.get());

        out = server.onPacket(out.get(0));
        assertEquals(SctpAssociation.State.ESTABLISHED, serverState.get());

        client.onPacket(out.get(0));
        assertEquals(SctpAssociation.State.ESTABLISHED, clientState.get());
    }

    @Test
    public void testDataHandlerCallback() {
        SctpAssociation client = new SctpAssociation(5000, 5000, true);
        SctpAssociation server = new SctpAssociation(5000, 5000, false);

        // Handshake
        List<byte[]> out = client.connect();
        out = server.onPacket(out.get(0));
        out = client.onPacket(out.get(0));
        out = server.onPacket(out.get(0));
        client.onPacket(out.get(0));
        assertEstablished(client, server);

        AtomicInteger streamId = new AtomicInteger(-1);
        AtomicReference<Long> ppid = new AtomicReference<>(-1L);
        AtomicReference<byte[]> payload = new AtomicReference<>();
        AtomicReference<Boolean> unordered = new AtomicReference<>();

        server.setDataHandler((sid, p, d, u) -> {
            streamId.set(sid);
            ppid.set(p);
            payload.set(d);
            unordered.set(u);
        });

        byte[] testData = "test payload".getBytes();
        byte[] dp = client.createDataPacket(1, PPID_WEBRTC_BINARY, testData, true);
        server.onPacket(dp);

        assertEquals(1, streamId.get());
        assertEquals((long) PPID_WEBRTC_BINARY, (long) ppid.get());
        assertArrayEquals(testData, payload.get());
        assertTrue(unordered.get());
    }

    @Test
    public void testEmptyPacket() {
        SctpAssociation assoc = new SctpAssociation(5000, 5000, false);
        List<byte[]> result = assoc.onPacket(new byte[0]);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGarbagePacket() {
        SctpAssociation assoc = new SctpAssociation(5000, 5000, false);
        List<byte[]> result = assoc.onPacket(new byte[]{0, 1, 2, 3, 4, 5});
        assertTrue(result.isEmpty());
    }

    // ---- helpers ----

    private static void assertEstablished(SctpAssociation a, SctpAssociation b) {
        assertEquals(SctpAssociation.State.ESTABLISHED, a.getState());
        assertEquals(SctpAssociation.State.ESTABLISHED, b.getState());
    }
}
