package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RTP packet encode/decode unit tests.
 */
public class RtpPacketTest {

    @Test
    public void testEncodeDecodeRoundTrip() {
        byte[] payload = "Hello RTP!".getBytes();
        long[] csrcs = new long[]{0x01020304L, 0x05060708L};

        RtpPacket pkt = new RtpPacket(2, false, false, 2, true, 111,
            12345, 1000000L, 0xDEADBEEFL, csrcs, payload);

        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);

        assertEquals(pkt, decoded);
        assertEquals(2, decoded.getVersion());
        assertFalse(decoded.hasPadding());
        assertFalse(decoded.hasExtension());
        assertEquals(2, decoded.getCsrcCount());
        assertTrue(decoded.getMarker());
        assertEquals(111, decoded.getPayloadType());
        assertEquals(12345, decoded.getSequenceNumber());
        assertEquals(1000000L, decoded.getTimestamp());
        assertEquals(0xDEADBEEFL, decoded.getSsrc());
        assertArrayEquals(csrcs, decoded.getCsrcList());
        assertArrayEquals(payload, decoded.getPayload());
    }

    @Test
    public void testDecodeMinimalHeader() {
        // Minimal 12-byte RTP header with no payload
        byte[] data = new byte[12];
        data[0] = (byte) 0x80; // V=2, no padding, no extension, CC=0
        data[1] = (byte) 0x00; // M=0, PT=0
        data[2] = 0x00;
        data[3] = 0x01; // seq=1
        // timestamp = 0
        // SSRC = 0

        RtpPacket pkt = RtpPacket.decode(data);
        assertEquals(2, pkt.getVersion());
        assertEquals(0, pkt.getPayloadType());
        assertEquals(1, pkt.getSequenceNumber());
        assertEquals(0, pkt.getPayload().length);
    }

    @Test
    public void testDecodeWithPayload() {
        byte[] data = new byte[16];
        data[0] = (byte) 0x80; // V=2
        data[1] = (byte) 0x6F; // M=0, PT=111
        data[2] = 0x00;
        data[3] = 0x01; // seq=1
        for (int i = 4; i < 8; i++) data[i] = 0;
        for (int i = 8; i < 12; i++) data[i] = 0; // SSRC=0
        // payload = "abcd"
        data[12] = 'a';
        data[13] = 'b';
        data[14] = 'c';
        data[15] = 'd';

        RtpPacket pkt = RtpPacket.decode(data);
        assertEquals(111, pkt.getPayloadType());
        assertEquals(4, pkt.getPayload().length);
        assertEquals('a', pkt.getPayload()[0]);
        assertEquals('d', pkt.getPayload()[3]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeVersionMismatch() {
        byte[] data = new byte[12];
        data[0] = (byte) 0xC0; // V=3
        RtpPacket.decode(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeTooShort() {
        RtpPacket.decode(new byte[5]);
    }

    @Test
    public void testEncodeHeaderOnly() {
        byte[] payload = "payload".getBytes();
        RtpPacket pkt = new RtpPacket(2, false, false, 0, false, 96,
            1, 2000L, 0x12345678L, null, payload);

        byte[] header = pkt.encodeHeader();
        assertEquals(12, header.length);
        // Verify header bytes match expectations
        assertEquals(0x80, header[0] & 0xFF); // V=2, CC=0
        assertEquals(96, header[1] & 0x7F);  // PT=96
    }

    @Test
    public void testDecodeWithExtensionBit() {
        byte[] data = new byte[12];
        data[0] = (byte) 0x90; // V=2, X=1, CC=0
        data[1] = (byte) 0x00;
        data[2] = 0x00;
        data[3] = 0x01;

        RtpPacket pkt = RtpPacket.decode(data);
        assertTrue(pkt.hasExtension());
        assertEquals(2, pkt.getVersion());
    }

    @Test
    public void testMultipleSequenceNumbers() {
        byte[] payload = "data".getBytes();
        RtpPacket pkt1 = new RtpPacket(2, false, false, 0, false, 0,
            0, 0L, 1L, null, payload);
        RtpPacket pkt2 = new RtpPacket(2, false, false, 0, false, 0,
            1, 0L, 1L, null, payload);

        byte[] e1 = pkt1.encode();
        byte[] e2 = pkt2.encode();

        assertEquals(0, e1[3] & 0xFF);
        assertEquals(1, e2[3] & 0xFF);
    }

    @Test
    public void testPayloadTypePreserved() {
        RtpPacket pkt = new RtpPacket(2, false, false, 0, true, 127,
            0, 0L, 0L, null, new byte[0]);
        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);
        assertEquals(127, decoded.getPayloadType());
        assertTrue(decoded.getMarker());
    }

    @Test
    public void testSsrcTimestampPreserved() {
        RtpPacket pkt = new RtpPacket(2, false, false, 0, false, 96,
            1, 0xABCD1234L, 0x5678DEADL, null, new byte[]{1, 2, 3});
        byte[] encoded = pkt.encode();
        RtpPacket decoded = RtpPacket.decode(encoded);
        assertEquals(0xABCD1234L, decoded.getTimestamp());
        assertEquals(0x5678DEADL, decoded.getSsrc());
    }
}
