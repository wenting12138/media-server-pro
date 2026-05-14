package com.wenting.mediaserver.protocol.webrtc.core.srtp;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * SRTP crypto context for SRTP_AES128_CM_HMAC_SHA1_80.
 */
public final class SrtpCryptoContext {

    private static final int MASTER_KEY_LEN = 16;
    private static final int MASTER_SALT_LEN = 14;
    private static final int SRTP_ENC_KEY_LEN = 16;
    private static final int SRTP_AUTH_KEY_LEN = 20;
    private static final int SRTP_SALT_KEY_LEN = 14;
    public static final int AUTH_TAG_LENGTH = 10;
    private static final String HMAC_SHA1 = "HmacSHA1";
    private static final String AES_ECB_NO_PADDING = "AES/ECB/NoPadding";

    private final byte[] cipherKey;
    private final byte[] authKey;
    private final byte[] saltKey;

    private volatile int roc;
    private volatile int lastSeq = -1;

    private final ThreadLocal<Cipher> aesCipher = new ThreadLocal<Cipher>();
    private final ThreadLocal<Mac> hmac = new ThreadLocal<Mac>();

    public SrtpCryptoContext(byte[] masterKey, byte[] masterSalt) {
        if (masterKey == null || masterKey.length != MASTER_KEY_LEN) {
            throw new IllegalArgumentException("Master key must be 16 bytes");
        }
        if (masterSalt == null || masterSalt.length != MASTER_SALT_LEN) {
            throw new IllegalArgumentException("Master salt must be 14 bytes");
        }
        this.cipherKey = deriveSessionMaterial(masterKey, masterSalt, 0x00, SRTP_ENC_KEY_LEN);
        this.authKey = deriveSessionMaterial(masterKey, masterSalt, 0x01, SRTP_AUTH_KEY_LEN);
        this.saltKey = deriveSessionMaterial(masterKey, masterSalt, 0x02, SRTP_SALT_KEY_LEN);
    }

    public long getRoc() {
        return roc & 0xFFFFFFFFL;
    }

    public void setRoc(long value) {
        this.roc = (int) (value & 0xFFFFFFFFL);
    }

    public synchronized void updateRoc(int sequenceNumber) {
        int seq = sequenceNumber & 0xFFFF;
        if (lastSeq >= 0) {
            int delta = seq - lastSeq;
            if (delta < -32768) {
                roc++;
            }
        }
        lastSeq = seq;
    }

    public byte[] encrypt(long ssrc, int sequenceNumber, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        }
        long index = ((getRoc() << 16) | (sequenceNumber & 0xFFFFL));
        byte[] result = Arrays.copyOf(payload, payload.length);
        xorAesCtr(result, 0, result.length, createRtpIv(saltKey, index, (int) (ssrc & 0xFFFFFFFFL)), cipher());
        return result;
    }

    public byte[] decrypt(long ssrc, int sequenceNumber, byte[] encryptedPayload) {
        if (encryptedPayload == null || encryptedPayload.length == 0) {
            return encryptedPayload == null ? new byte[0] : Arrays.copyOf(encryptedPayload, encryptedPayload.length);
        }
        long index = ((getRoc() << 16) | (sequenceNumber & 0xFFFFL));
        byte[] result = Arrays.copyOf(encryptedPayload, encryptedPayload.length);
        xorAesCtr(result, 0, result.length, createRtpIv(saltKey, index, (int) (ssrc & 0xFFFFFFFFL)), cipher());
        return result;
    }

    public byte[] authenticate(byte[] rtpHeader, byte[] payload, long ssrc, long roc) {
        Mac mac = hmac();
        mac.update(rtpHeader);
        mac.update(payload);
        mac.update((byte) ((roc >>> 24) & 0xFF));
        mac.update((byte) ((roc >>> 16) & 0xFF));
        mac.update((byte) ((roc >>> 8) & 0xFF));
        mac.update((byte) (roc & 0xFF));
        byte[] full = mac.doFinal();
        return Arrays.copyOf(full, AUTH_TAG_LENGTH);
    }

    public boolean verifyAuthTag(byte[] rtpHeader, byte[] payload,
                                 long ssrc, long roc, byte[] receivedTag) {
        if (receivedTag == null || receivedTag.length != AUTH_TAG_LENGTH) {
            return false;
        }
        byte[] expected = authenticate(rtpHeader, payload, ssrc, roc);
        int diff = 0;
        for (int i = 0; i < AUTH_TAG_LENGTH; i++) {
            diff |= (expected[i] ^ receivedTag[i]);
        }
        return diff == 0;
    }

    public static SrtpCryptoContext fromKeyMaterial(byte[] keyMaterial, boolean isClient) {
        if (keyMaterial == null || keyMaterial.length < 2 * (MASTER_KEY_LEN + MASTER_SALT_LEN)) {
            throw new IllegalArgumentException("Key material must be at least 60 bytes");
        }
        int clientKeyOffset = 0;
        int serverKeyOffset = MASTER_KEY_LEN;
        int clientSaltOffset = 2 * MASTER_KEY_LEN;
        int serverSaltOffset = clientSaltOffset + MASTER_SALT_LEN;

        byte[] masterKey = new byte[MASTER_KEY_LEN];
        byte[] masterSalt = new byte[MASTER_SALT_LEN];
        if (isClient) {
            System.arraycopy(keyMaterial, clientKeyOffset, masterKey, 0, MASTER_KEY_LEN);
            System.arraycopy(keyMaterial, clientSaltOffset, masterSalt, 0, MASTER_SALT_LEN);
        } else {
            System.arraycopy(keyMaterial, serverKeyOffset, masterKey, 0, MASTER_KEY_LEN);
            System.arraycopy(keyMaterial, serverSaltOffset, masterSalt, 0, MASTER_SALT_LEN);
        }
        return new SrtpCryptoContext(masterKey, masterSalt);
    }

    private byte[] deriveSessionMaterial(byte[] masterKey, byte[] masterSalt, int label, int outLen) {
        byte[] input = new byte[16];
        System.arraycopy(masterSalt, 0, input, 0, MASTER_SALT_LEN);
        input[7] ^= (byte) (label & 0xFF);
        byte[] out = new byte[outLen];
        byte[] ctr = Arrays.copyOf(input, input.length);
        Cipher cipher = newEcbCipher(masterKey);
        int outPos = 0;
        int blockCounter = 0;
        while (outPos < outLen) {
            ctr[14] = (byte) ((blockCounter >>> 8) & 0xFF);
            ctr[15] = (byte) (blockCounter & 0xFF);
            byte[] block = doAesBlock(cipher, ctr);
            int chunk = Math.min(16, outLen - outPos);
            System.arraycopy(block, 0, out, outPos, chunk);
            outPos += chunk;
            blockCounter++;
        }
        return out;
    }

    private static byte[] createRtpIv(byte[] saltKey, long index, int ssrc) {
        byte[] iv = new byte[16];
        iv[4] = (byte) ((ssrc >>> 24) & 0xFF);
        iv[5] = (byte) ((ssrc >>> 16) & 0xFF);
        iv[6] = (byte) ((ssrc >>> 8) & 0xFF);
        iv[7] = (byte) (ssrc & 0xFF);
        byte[] indexBytes = new byte[8];
        indexBytes[0] = (byte) ((index >>> 56) & 0xFF);
        indexBytes[1] = (byte) ((index >>> 48) & 0xFF);
        indexBytes[2] = (byte) ((index >>> 40) & 0xFF);
        indexBytes[3] = (byte) ((index >>> 32) & 0xFF);
        indexBytes[4] = (byte) ((index >>> 24) & 0xFF);
        indexBytes[5] = (byte) ((index >>> 16) & 0xFF);
        indexBytes[6] = (byte) ((index >>> 8) & 0xFF);
        indexBytes[7] = (byte) (index & 0xFF);
        for (int i = 0; i < 8; i++) {
            iv[6 + i] ^= indexBytes[i];
        }
        for (int i = 0; i < MASTER_SALT_LEN; i++) {
            iv[i] ^= saltKey[i];
        }
        return iv;
    }

    private void xorAesCtr(byte[] packet, int offset, int length, byte[] iv, Cipher cipher) {
        if (length <= 0) {
            return;
        }
        byte[] ctr = Arrays.copyOf(iv, iv.length);
        int outPos = 0;
        int blockCounter = 0;
        while (outPos < length) {
            ctr[14] = (byte) ((blockCounter >>> 8) & 0xFF);
            ctr[15] = (byte) (blockCounter & 0xFF);
            byte[] keystream = doAesBlock(cipher, ctr);
            int chunk = Math.min(16, length - outPos);
            for (int i = 0; i < chunk; i++) {
                packet[offset + outPos + i] = (byte) (packet[offset + outPos + i] ^ keystream[i]);
            }
            outPos += chunk;
            blockCounter++;
        }
    }

    private Cipher cipher() {
        Cipher c = aesCipher.get();
        if (c != null) {
            return c;
        }
        try {
            c = Cipher.getInstance(AES_ECB_NO_PADDING);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"));
            aesCipher.set(c);
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init SRTP AES cipher", e);
        }
    }

    private Mac hmac() {
        Mac m = hmac.get();
        if (m != null) {
            m.reset();
            return m;
        }
        try {
            m = Mac.getInstance(HMAC_SHA1);
            m.init(new SecretKeySpec(authKey, HMAC_SHA1));
            hmac.set(m);
            return m;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init SRTP HMAC", e);
        }
    }

    private static Cipher newEcbCipher(byte[] key) {
        try {
            Cipher c = Cipher.getInstance(AES_ECB_NO_PADDING);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to init AES cipher", e);
        }
    }

    private static byte[] doAesBlock(Cipher cipher, byte[] block16) {
        try {
            return cipher.doFinal(block16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SRTP AES block encrypt failed", e);
        }
    }
}
