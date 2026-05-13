package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.sctp.*;
import org.junit.Test;

import java.util.Collections;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpChunk.*;
import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;
import static org.junit.Assert.*;

/**
 * SCTP chunk and packet encode/decode round-trip tests (RFC 4960).
 */
public class SctpPacketTest {

    @Test
    public void testInitEncodeDecode() {
        Init init = new Init(0x12345678L, 65536, 64, 64, 1000);
        byte[] encoded = init.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof Init);
        Init d = (Init) decoded;
        assertEquals(0x12345678L, d.initiateTag);
        assertEquals(65536L, d.advertisedRwnd);
        assertEquals(64, d.os);
        assertEquals(64, d.miss);
        assertEquals(1000L, d.initialTsn);
    }

    @Test
    public void testInitAckEncodeDecode() {
        byte[] cookie = "test-state-cookie".getBytes();
        InitAck ack = new InitAck(0x87654321L, 65536, 32, 32, 2000, cookie);
        byte[] encoded = ack.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof InitAck);
        InitAck d = (InitAck) decoded;
        assertEquals(0x87654321L, d.initiateTag);
        assertArrayEquals(cookie, d.stateCookie);
    }

    @Test
    public void testCookieEchoEncodeDecode() {
        byte[] cookie = "state-cookie-data".getBytes();
        CookieEcho echo = new CookieEcho(cookie);
        byte[] encoded = echo.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof CookieEcho);
        assertArrayEquals(cookie, ((CookieEcho) decoded).cookie);
    }

    @Test
    public void testCookieAckEncodeDecode() {
        CookieAck ack = new CookieAck();
        byte[] encoded = ack.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof CookieAck);
    }

    @Test
    public void testDataEncodeDecode() {
        byte[] payload = "Hello SCTP".getBytes();
        Data data = Data.create(12345, 1, 0, PPID_WEBRTC_STRING, payload, true);
        byte[] encoded = data.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof Data);
        Data d = (Data) decoded;
        assertEquals(12345L, d.tsn);
        assertEquals(1, d.streamId);
        assertEquals(PPID_WEBRTC_STRING, d.ppid);
        assertArrayEquals(payload, d.userData);
        assertTrue(d.unordered);
        assertTrue(d.begin);
        assertTrue(d.end);
    }

    @Test
    public void testDataOrderedFlag() {
        byte[] payload = "ordered".getBytes();
        Data data = Data.create(99, 0, 0, PPID_WEBRTC_STRING, payload, false);
        assertFalse(data.unordered);
        assertTrue(data.begin);
        assertTrue(data.end);

        byte[] encoded = data.encode();
        Data decoded = (Data) SctpChunk.decode(encoded, 0);
        assertFalse(decoded.unordered);
    }

    @Test
    public void testSackEncodeDecode() {
        Sack sack = new Sack(100, 65536);
        byte[] encoded = sack.encode();
        SctpChunk decoded = SctpChunk.decode(encoded, 0);
        assertTrue(decoded instanceof Sack);
        Sack d = (Sack) decoded;
        assertEquals(100L, d.cumulativeTsnAck);
        assertEquals(65536L, d.advertisedRwnd);
    }

    @Test
    public void testSctpPacketEncodeDecodeRoundTrip() {
        Data data = Data.create(42, 0, 0, PPID_WEBRTC_STRING, "test".getBytes(), false);
        SctpPacket pkt = new SctpPacket(5000, 5001, 0x12345678L,
            Collections.<SctpChunk>singletonList(data));
        byte[] encoded = pkt.encode();

        SctpPacket decoded = SctpPacket.decode(encoded);
        assertEquals(5000, decoded.getSourcePort());
        assertEquals(5001, decoded.getDestPort());
        assertEquals(0x12345678L, decoded.getVerificationTag());
        assertEquals(1, decoded.getChunks().size());
        assertTrue(decoded.getChunks().get(0) instanceof Data);
    }

    @Test
    public void testPacketWithMultipleChunks() {
        Init init = new Init(0x1111L, 65536, 64, 64, 100);
        Sack sack = new Sack(50, 32768);
        SctpPacket pkt = new SctpPacket(5000, 5000, 0L,
            new java.util.ArrayList<SctpChunk>() {{ add(init); add(sack); }});
        byte[] encoded = pkt.encode();

        SctpPacket decoded = SctpPacket.decode(encoded);
        assertEquals(2, decoded.getChunks().size());
        assertTrue(decoded.getChunks().get(0) instanceof Init);
        assertTrue(decoded.getChunks().get(1) instanceof Sack);
    }

    @Test
    public void testDataPaddingTo4Bytes() {
        // 1 byte payload needs 3 bytes padding to reach 4-byte boundary
        byte[] payload = new byte[]{0x01};
        Data data = Data.create(1, 0, 0, PPID_WEBRTC_BINARY, payload, false);
        byte[] encoded = data.encode();

        int length = ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF);
        assertEquals(0, length % 4); // Padded to 4-byte boundary

        // Verify decode still works
        Data decoded = (Data) SctpChunk.decode(encoded, 0);
        assertArrayEquals(payload, decoded.userData);
    }

    @Test
    public void testUnknownChunkType() {
        byte[] rawChunk = new byte[8];
        rawChunk[0] = (byte) 0xFF; // Unknown type
        rawChunk[1] = 0;
        rawChunk[2] = 0;
        rawChunk[3] = 8; // length (4 header + 4 data, but data is padding)

        SctpChunk chunk = SctpChunk.decode(rawChunk, 0);
        assertNotNull(chunk);
        assertEquals(0xFF, chunk.getType());
    }

    @Test
    public void testSctpPacketTooShort() {
        try {
            SctpPacket.decode(new byte[4]);
            fail("Expected exception for too-short packet");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void testDataPpidValues() {
        // String PPID
        Data strData = Data.create(1, 0, 0, PPID_WEBRTC_STRING, "hello".getBytes(), false);
        byte[] enc = strData.encode();
        assertEquals(PPID_WEBRTC_STRING, ((Data) SctpChunk.decode(enc, 0)).ppid);

        // Binary PPID
        Data binData = Data.create(2, 0, 0, PPID_WEBRTC_BINARY, new byte[]{1, 2, 3}, false);
        enc = binData.encode();
        assertEquals(PPID_WEBRTC_BINARY, ((Data) SctpChunk.decode(enc, 0)).ppid);
    }
}
