package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests for SRTP cryptographic context (AES-CM + HMAC-SHA1).
 */
public class SrtpCryptoContextTest {

    private static final byte[] TEST_MASTER_KEY = new byte[]{
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    };
    private static final byte[] TEST_MASTER_SALT = new byte[]{
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
        0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D
    };

    @Test
    public void testEncryptDecryptRoundTrip() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] plaintext = "Hello WebRTC SRTP!".getBytes();
        long ssrc = 0x12345678L;
        int seq = 1000;

        byte[] encrypted = ctx.encrypt(ssrc, seq, plaintext);
        assertNotNull("Encrypted output should not be null", encrypted);
        assertEquals("Encrypted length should match plaintext", plaintext.length, encrypted.length);
        assertFalse("Encrypted should differ from plaintext", Arrays.equals(plaintext, encrypted));

        byte[] decrypted = ctx.decrypt(ssrc, seq, encrypted);
        assertArrayEquals("Decrypted should match original plaintext", plaintext, decrypted);
    }

    @Test
    public void testEncryptWithDifferentKeys() {
        SrtpCryptoContext ctx1 = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] diffKey = new byte[16];
        Arrays.fill(diffKey, (byte) 0xFF);
        SrtpCryptoContext ctx2 = new SrtpCryptoContext(diffKey, TEST_MASTER_SALT);

        byte[] plaintext = "test data".getBytes();
        long ssrc = 1;
        int seq = 5;

        byte[] enc1 = ctx1.encrypt(ssrc, seq, plaintext);
        byte[] enc2 = ctx2.encrypt(ssrc, seq, plaintext);

        assertFalse("Different keys should produce different ciphertext",
            Arrays.equals(enc1, enc2));
    }

    @Test
    public void testEncryptWithDifferentSequence() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] plaintext = "data".getBytes();
        long ssrc = 1;

        byte[] enc1 = ctx.encrypt(ssrc, 0, plaintext);
        byte[] enc2 = ctx.encrypt(ssrc, 1, plaintext);

        assertFalse("Different sequence numbers should produce different ciphertext",
            Arrays.equals(enc1, enc2));
    }

    @Test
    public void testAuthTagGeneration() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] rtpHeader = new byte[]{
            (byte) 0x80, 0x00, 0x00, 0x01, // V=2, P=0, X=0, CC=0, M=0, PT=0, seq=1
            0x00, 0x00, 0x00, 0x00,       // timestamp
            0x12, 0x34, 0x56, 0x78        // SSRC
        };
        byte[] payload = "authenticate me".getBytes();
        long ssrc = 0x12345678L;
        long roc = 0;

        byte[] tag = ctx.authenticate(rtpHeader, payload, ssrc, roc);
        assertNotNull("Auth tag should not be null", tag);
        assertEquals("Auth tag should be 10 bytes (80-bit)",
            SrtpCryptoContext.AUTH_TAG_LENGTH, tag.length);

        assertTrue("Valid auth tag should verify",
            ctx.verifyAuthTag(rtpHeader, payload, ssrc, roc, tag));
    }

    @Test
    public void testAuthTagMismatch() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] rtpHeader = new byte[]{ (byte) 0x80, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78 };
        byte[] payload = "data".getBytes();
        long ssrc = 0x12345678L;
        long roc = 0;

        byte[] tag = ctx.authenticate(rtpHeader, payload, ssrc, roc);
        byte[] wrongTag = Arrays.copyOf(tag, tag.length);
        wrongTag[0] ^= 0xFF; // flip all bits in first byte

        assertFalse("Wrong tag should fail verification",
            ctx.verifyAuthTag(rtpHeader, payload, ssrc, roc, wrongTag));
    }

    @Test
    public void testAuthTagWithWrongRoc() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        byte[] rtpHeader = new byte[]{ (byte) 0x80, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78 };
        byte[] payload = "data".getBytes();
        long ssrc = 0x12345678L;

        byte[] tag = ctx.authenticate(rtpHeader, payload, ssrc, 0);
        assertFalse("Wrong ROC should fail verification",
            ctx.verifyAuthTag(rtpHeader, payload, ssrc, 1, tag));
    }

    @Test
    public void testRocManagement() {
        SrtpCryptoContext ctx = new SrtpCryptoContext(TEST_MASTER_KEY, TEST_MASTER_SALT);
        assertEquals("Initial ROC should be 0", 0, ctx.getRoc());

        ctx.updateRoc(0x1234);
        assertEquals("ROC should not increment on regular sequence", 0, ctx.getRoc());

        ctx.updateRoc(0xFFFF);
        assertEquals("ROC should increment when seq wraps to 0xFFFF", 1, ctx.getRoc());

        ctx.setRoc(100);
        assertEquals("setRoc should update value", 100, ctx.getRoc());
    }

    @Test
    public void testFromKeyMaterial() {
        byte[] keyMaterial = new byte[60];
        for (int i = 0; i < 60; i++) {
            keyMaterial[i] = (byte) i;
        }

        SrtpCryptoContext clientCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, true);
        SrtpCryptoContext serverCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, false);

        assertNotNull("Client context from key material should not be null", clientCtx);
        assertNotNull("Server context from key material should not be null", serverCtx);

        byte[] data = "cross-context encrypt".getBytes();
        long ssrc = 0x12345678L;
        int seq = 42;

        // Encrypt and decrypt with the same client key (loopback)
        byte[] encrypted = clientCtx.encrypt(ssrc, seq, data);
        byte[] decrypted = clientCtx.decrypt(ssrc, seq, encrypted);
        assertArrayEquals("Client key encrypt/decrypt loopback should work", data, decrypted);

        // Encrypt and decrypt with the same server key (loopback)
        encrypted = serverCtx.encrypt(ssrc, seq, data);
        decrypted = serverCtx.decrypt(ssrc, seq, encrypted);
        assertArrayEquals("Server key encrypt/decrypt loopback should work", data, decrypted);

        // Client and server keys should be different (different ciphertexts)
        byte[] clientEnc = clientCtx.encrypt(ssrc, seq, data);
        byte[] serverEnc = serverCtx.encrypt(ssrc, seq, data);
        assertFalse("Client and server keys should produce different ciphertexts",
            Arrays.equals(clientEnc, serverEnc));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidKeySize() {
        new SrtpCryptoContext(new byte[8], TEST_MASTER_SALT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSaltSize() {
        new SrtpCryptoContext(TEST_MASTER_KEY, new byte[4]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFromKeyMaterialTooShort() {
        SrtpCryptoContext.fromKeyMaterial(new byte[30], true);
    }
}
