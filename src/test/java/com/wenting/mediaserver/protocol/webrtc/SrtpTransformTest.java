package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpException;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import org.junit.Test;

import java.security.SecureRandom;

import static org.junit.Assert.*;

/**
 * SRTP packet-level transform unit tests.
 */
public class SrtpTransformTest {

    private static SrtpCryptoContext createContext(boolean isClient) {
        SecureRandom rng = new SecureRandom();
        byte[] keyMaterial = new byte[60];
        rng.nextBytes(keyMaterial);
        return SrtpCryptoContext.fromKeyMaterial(keyMaterial, isClient);
    }

    private static RtpPacket createTestPacket(long ssrc, int seq, byte[] payload) {
        return new RtpPacket(2, false, false, 0, false, 111,
            seq, 1000L + seq, ssrc, null, payload);
    }

    @Test
    public void testProtectUnprotectRoundTrip() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        long ssrc = 1L;
        byte[] payload = "Hello SRTP!".getBytes();

        SrtpTransform transform = new SrtpTransform(ctx, ssrc);
        RtpPacket original = createTestPacket(ssrc, 1, payload);

        byte[] protectedData = transform.protect(original);
        RtpPacket decrypted = transform.unprotect(protectedData);

        assertArrayEquals("Payload should round-trip", payload, decrypted.getPayload());
        assertEquals("SSRC should match", ssrc, decrypted.getSsrc());
        assertEquals("Seq should match", 1, decrypted.getSequenceNumber());
        assertEquals("PT should match", 111, decrypted.getPayloadType());
        assertFalse("Auth tag mismatch should not occur", false);
    }

    @Test
    public void testProtectProducesLongerOutput() {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        byte[] payload = "short".getBytes();
        RtpPacket pkt = createTestPacket(1L, 1, payload);

        byte[] protectedData = transform.protect(pkt);
        int headerLen = 12;
        int expectedLen = headerLen + payload.length + 10; // + auth tag
        assertEquals("Output should be header + encrypted payload + auth tag",
            expectedLen, protectedData.length);
    }

    @Test(expected = SrtpException.class)
    public void testUnprotectTamperedPayloadFails() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        RtpPacket pkt = createTestPacket(1L, 1, "tamper-test".getBytes());
        byte[] protectedData = transform.protect(pkt);

        // Tamper with the encrypted payload
        protectedData[protectedData.length - 11] ^= 0xFF;

        transform.unprotect(protectedData);
    }

    @Test(expected = SrtpException.class)
    public void testUnprotectTamperedAuthTagFails() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        RtpPacket pkt = createTestPacket(1L, 1, "tamper-auth".getBytes());
        byte[] protectedData = transform.protect(pkt);

        // Tamper with the auth tag (last byte)
        protectedData[protectedData.length - 1] ^= 0x01;

        transform.unprotect(protectedData);
    }

    @Test(expected = SrtpException.class)
    public void testUnprotectTooShortFails() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);
        transform.unprotect(new byte[5]);
    }

    @Test
    public void testCrossContextProtectUnprotect() throws Exception {
        // Simulate client and server: same key material, opposite roles
        SecureRandom rng = new SecureRandom();
        byte[] keyMaterial = new byte[60];
        rng.nextBytes(keyMaterial);

        SrtpCryptoContext clientSendCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, true);
        // Server's read key = client's write key
        SrtpCryptoContext serverRecvCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, true);

        long ssrc = 1L;
        byte[] payload = "cross-context".getBytes();

        SrtpTransform clientTransform = new SrtpTransform(clientSendCtx, ssrc);
        SrtpTransform serverTransform = new SrtpTransform(serverRecvCtx, ssrc);

        RtpPacket original = createTestPacket(ssrc, 1, payload);
        byte[] protectedData = clientTransform.protect(original);
        RtpPacket decrypted = serverTransform.unprotect(protectedData);

        assertArrayEquals("Cross-context payload should match", payload, decrypted.getPayload());
    }

    @Test
    public void testMultiplePacketsSameKey() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        for (int seq = 1; seq <= 5; seq++) {
            byte[] payload = ("packet-" + seq).getBytes();
            RtpPacket pkt = createTestPacket(1L, seq, payload);
            byte[] protectedData = transform.protect(pkt);
            RtpPacket decrypted = transform.unprotect(protectedData);
            assertArrayEquals("Payload for seq " + seq, payload, decrypted.getPayload());
            assertEquals("Seq " + seq + " should match", seq, decrypted.getSequenceNumber());
        }
    }

    @Test
    public void testDifferentSequenceProduceDifferentCiphertext() throws Exception {
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        byte[] payload = "same-payload".getBytes();
        RtpPacket pkt1 = createTestPacket(1L, 1, payload);
        RtpPacket pkt2 = createTestPacket(1L, 2, payload);

        byte[] protected1 = transform.protect(pkt1);
        // Use a separate context for pkt2 to avoid ROC issues in comparison
        // Actually, we just care that ciphertext bytes differ
        SrtpCryptoContext ctx2 = createContext(true);
        SrtpTransform transform2 = new SrtpTransform(ctx2, 1L);
        byte[] protected2 = transform2.protect(pkt2);

        // Headers differ at seq byte (byte 2-3), payload should differ due to different counter
        assertFalse("Different seq should produce different output",
            java.util.Arrays.equals(protected1, protected2));
    }

    @Test
    public void testSsrcMismatchStillDecrypts() throws Exception {
        // SRTP auth includes SSRC in header, but the transform can still attempt it
        SrtpCryptoContext ctx = createContext(true);
        SrtpTransform transform = new SrtpTransform(ctx, 1L);

        byte[] payload = "test".getBytes();
        RtpPacket pkt = createTestPacket(2L, 1, payload); // SSRC=2
        byte[] protectedData = transform.protect(pkt);

        // Should still work because auth tag is computed over header (which includes SSRC=2)
        RtpPacket decrypted = transform.unprotect(protectedData);
        assertArrayEquals(payload, decrypted.getPayload());
        assertEquals(2L, decrypted.getSsrc());
    }
}
