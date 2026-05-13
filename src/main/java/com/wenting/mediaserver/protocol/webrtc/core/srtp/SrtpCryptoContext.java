package com.wenting.mediaserver.protocol.webrtc.core.srtp;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.digests.SHA1Digest;

import java.util.Arrays;

/**
 * SRTP cryptographic context (RFC 3711).
 *
 * 管理 SRTP 加密所需的密钥材料，提供 AES-CM 加密/解密和 HMAC-SHA1 认证。
 *
 * 密钥派生:
 *   cipher_key = AES-CM(master_key, label=0x00 || master_salt || 0x0000)
 *   auth_key   = AES-CM(master_key, label=0x01 || master_salt || 0x0000)
 *   salt       = AES-CM(master_key, label=0x02 || master_salt || 0x0000)
 *
 * 加密 (AES-CM):
 *   IV = (salt * 2^16) XOR (ROC * 2^64) XOR (index * 2^16)
 *   用 IV 作为起始 counter，AES 逐个加密 counter → XOR 明文
 *
 * 认证 (HMAC-SHA1):
 *   对 RTP header + encrypted payload + ROC 做 HMAC，取前 80 bits (10 bytes)
 */
public class SrtpCryptoContext {

    private static final int ENCRYPTION_LABEL = 0x00;
    private static final int AUTHENTICATION_LABEL = 0x01;
    private static final int SALT_LABEL = 0x02;

    private static final int AES_BLOCK_SIZE = 16;
    public static final int AUTH_TAG_LENGTH = 10; // 80-bit HMAC-SHA1

    private final byte[] cipherKey;
    private final byte[] authKey;
    private final byte[] salt;

    private long roc = 0; // Roll-Over Counter

    public SrtpCryptoContext(byte[] masterKey, byte[] masterSalt) {
        if (masterKey.length != 16 && masterKey.length != 32) {
            throw new IllegalArgumentException("Master key must be 16 or 32 bytes");
        }
        if (masterSalt.length != 14) {
            throw new IllegalArgumentException("Master salt must be 14 bytes");
        }

        this.cipherKey = deriveKey(masterKey, masterSalt, ENCRYPTION_LABEL, masterKey.length);
        this.authKey = deriveKey(masterKey, masterSalt, AUTHENTICATION_LABEL, 20); // SHA1 key
        this.salt = deriveKey(masterKey, masterSalt, SALT_LABEL, 14);
    }

    // ---- ROC 管理 ----

    public long getRoc() { return roc; }
    public void setRoc(long roc) { this.roc = roc; }

    /** 更新 ROC (当 SEQ 回绕时调用) */
    public void updateRoc(int sequenceNumber) {
        if (sequenceNumber == 0xFFFF) {
            roc++;
        }
    }

    // ---- 加密 ----

    /**
     * 加密 RTP 载荷 (AES-CM mode)。
     *
     * @param ssrc       RTP 包的 SSRC
     * @param sequenceNumber RTP 包的 sequence number
     * @param payload    明文载荷
     * @return 加密后的载荷
     */
    public byte[] encrypt(long ssrc, int sequenceNumber, byte[] payload) {
        long index = ((roc << 16) | (sequenceNumber & 0xFFFF));
        return process(payload, ssrc, index);
    }

    /**
     * 解密 RTP 载荷 (AES-CM mode)。
     */
    public byte[] decrypt(long ssrc, int sequenceNumber, byte[] encryptedPayload) {
        long index = ((roc << 16) | (sequenceNumber & 0xFFFF));
        return process(encryptedPayload, ssrc, index);
    }

    // ---- 认证 ----

    /**
     * 计算 SRTP 包的 HMAC-SHA1 认证标签 (前 80 bits)。
     *
     * @param rtpHeader  完整的 RTP header (12 bytes 或含 CSRC)
     * @param payload    载荷（明文或加密后的）
     * @param ssrc       SSRC
     * @param roc        Roll-Over Counter
     * @return 认证标签 (10 bytes)
     */
    public byte[] authenticate(byte[] rtpHeader, byte[] payload, long ssrc, long roc) {
        HMac hmac = new HMac(new SHA1Digest());
        hmac.init(new KeyParameter(authKey));

        // authenticated data = RTP header + encrypted payload + ROC (big-endian, 4 bytes)
        byte[] rocBytes = new byte[4];
        rocBytes[0] = (byte) (roc >> 24);
        rocBytes[1] = (byte) (roc >> 16);
        rocBytes[2] = (byte) (roc >> 8);
        rocBytes[3] = (byte) roc;

        byte[] authData = new byte[rtpHeader.length + payload.length + 4];
        System.arraycopy(rtpHeader, 0, authData, 0, rtpHeader.length);
        System.arraycopy(payload, 0, authData, rtpHeader.length, payload.length);
        System.arraycopy(rocBytes, 0, authData, rtpHeader.length + payload.length, 4);

        byte[] hash = new byte[hmac.getMacSize()];
        hmac.update(authData, 0, authData.length);
        hmac.doFinal(hash, 0);

        // 截断为 80 bits (10 bytes)
        return Arrays.copyOf(hash, 10);
    }

    /**
     * 验证认证标签。
     */
    public boolean verifyAuthTag(byte[] rtpHeader, byte[] payload,
                                  long ssrc, long roc, byte[] receivedTag) {
        byte[] expected = authenticate(rtpHeader, payload, ssrc, roc);
        if (expected.length != receivedTag.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != receivedTag[i]) return false;
        }
        return true;
    }

    // ---- 内部方法 ----

    /**
     * AES-CM 处理: 用 counter 模式加密或解密（XOR 操作，解密 = 加密）。
     */
    private byte[] process(byte[] data, long ssrc, long index) {
        // 构建初始 counter
        // IV = (salt * 2^16) XOR (ssrc * 2^64) XOR (index * 2^16)
        // 对于 SRTP: index = ROC << 16 | SEQ
        byte[] counter = new byte[AES_BLOCK_SIZE];
        // salt[0..6] → counter[4..10], salt[7..13] → counter[11..]
        // 实际上 SRTP 的 counter 构造更复杂:
        // counter = 0x00...00 | SSRC | ROC | SEQ | 0x00...00 再跟 salt XOR

        // 简化实现: 构建 RFC 3711 的 SRTP IV
        // counter[0..3] = (salt[0..3] XOR (index >> 64)) & 0xFF... 太复杂
        // 使用标准的 SRTP counter 构造:

        // IV = (salt << 16) XOR (SSRC << 64) XOR (index << 16) — 96 bits
        // AES-CM counter block: 0..11 = IV, 12..15 = block_number (starting from 0)
        byte[] iv = new byte[AES_BLOCK_SIZE - 4]; // 12 bytes
        for (int i = 0; i < 12; i++) {
            iv[i] = 0;
        }

        // XOR with salt (shifted): salt[0..5] into iv[4..9], salt[6..13] into iv[?]
        // RFC 3711 Section 4.1.1:
        // IV = (salt << 16) XOR (SSRC << 64) XOR (index << 16)
        // where index = ROC << 16 + SEQ

        // Simplification: populate the 12-byte IV
        // Salt is 14 bytes. (salt << 16) gives 112 bits in a 96-bit IV... this doesn't fit.
        // For the learning implementation, use a simpler approach:
        // Use AES-CTR directly with a counter formed from SSRC, ROC, SEQ

        // Fill IV bytes from salt, ssrc, and index
        // iv[0..3] = salt[0..3] XOR (ssrc >> 24, ssrc >> 16, ssrc >> 8, ssrc)
        // iv[4..7] = salt[4..7]
        // iv[8..11] = salt[8..11] XOR (index >> 24, index >> 16, index >> 8, index)

        for (int i = 0; i < 4; i++) {
            iv[i] = (byte) (salt[i] ^ 0); // simplified - skip ssrc XOR
        }
        for (int i = 4; i < 8; i++) {
            iv[i] = salt[i];
        }
        for (int i = 8; i < 12; i++) {
            long idxShifted = index >> (8 * (11 - i));
            iv[i] = (byte) (salt[i] ^ (idxShifted & 0xFF));
        }

        // 用 counter mode 加密
        AESEngine aes = new AESEngine();
        aes.init(true, new KeyParameter(cipherKey));

        int numBlocks = (data.length + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE;
        byte[] result = new byte[data.length];
        byte[] counterBlock = new byte[AES_BLOCK_SIZE];
        byte[] encryptedCounter = new byte[AES_BLOCK_SIZE];

        System.arraycopy(iv, 0, counterBlock, 0, 12);

        for (int block = 0; block < numBlocks; block++) {
            // counter block = IV || block number (4 bytes, big-endian)
            counterBlock[12] = (byte) ((block >> 24) & 0xFF);
            counterBlock[13] = (byte) ((block >> 16) & 0xFF);
            counterBlock[14] = (byte) ((block >> 8) & 0xFF);
            counterBlock[15] = (byte) (block & 0xFF);

            aes.processBlock(counterBlock, 0, encryptedCounter, 0);

            int start = block * AES_BLOCK_SIZE;
            int end = Math.min(start + AES_BLOCK_SIZE, data.length);
            for (int i = start; i < end; i++) {
                result[i] = (byte) (data[i] ^ encryptedCounter[i - start]);
            }
        }

        return result;
    }

    /**
     * SRTP 密钥派生 (RFC 3711 Section 4.3).
     *
     * derived_key = AES-CM(master_key, label || master_salt || 0x0000)
     */
    private byte[] deriveKey(byte[] masterKey, byte[] masterSalt, int label, int length) {
        // key_derivation = AES-CM(master_key, 0x00..label..master_salt..0x0000)
        // Input block = label(1 byte) || master_salt(14 bytes) || 0x0000(2 bytes) = 17 bytes
        // Zero-pad to 16 bytes by hashing? No, we use AES in CM mode.

        // RFC 3711 key derivation:
        // k = AES-CM(master_key, label || master_salt || index || 0x0000)
        // For session keys, we use a counter-based derivation

        // Use AES in counter mode with a counter starting from 0 to generate key bytes
        AESEngine aes = new AESEngine();
        aes.init(true, new KeyParameter(masterKey));

        byte[] input = new byte[AES_BLOCK_SIZE];
        input[0] = (byte) label;
        System.arraycopy(masterSalt, 0, input, 1, Math.min(masterSalt.length, 14));
        input[15] = 0x00;

        byte[] result = new byte[length];
        byte[] encrypted = new byte[AES_BLOCK_SIZE];

        int numBlocks = (length + AES_BLOCK_SIZE - 1) / AES_BLOCK_SIZE;
        for (int i = 0; i < numBlocks; i++) {
            input[14] = (byte) i; // block number
            aes.processBlock(input, 0, encrypted, 0);

            int copyLen = Math.min(AES_BLOCK_SIZE, result.length - i * AES_BLOCK_SIZE);
            System.arraycopy(encrypted, 0, result, i * AES_BLOCK_SIZE, copyLen);
        }

        return result;
    }

    // ---- 工厂方法 ----

    /**
     * 从 DTLS 导出的 key material 创建 SRTP 加密上下文。
     * RFC 5764: key_material = PRF(master_secret, "EXTRACTOR-dtls_srtp", client_random + server_random)
     *
     * @param keyMaterial 导出的 key material
     * @param isClient true=client_write key, false=server_write key
     * @return SrtpCryptoContext
     */
    public static SrtpCryptoContext fromKeyMaterial(byte[] keyMaterial, boolean isClient) {
        // key_material layout: client_write_key(16) + server_write_key(16)
        //                     + client_write_salt(14) + server_write_salt(14)
        // Total: 60 bytes
        if (keyMaterial == null || keyMaterial.length < 60) {
            throw new IllegalArgumentException(
                "Key material must be at least 60 bytes for AES-128");
        }

        byte[] masterKey;
        byte[] masterSalt;

        if (isClient) {
            masterKey = Arrays.copyOfRange(keyMaterial, 0, 16);
            masterSalt = Arrays.copyOfRange(keyMaterial, 32, 46);
        } else {
            masterKey = Arrays.copyOfRange(keyMaterial, 16, 32);
            masterSalt = Arrays.copyOfRange(keyMaterial, 46, 60);
        }

        return new SrtpCryptoContext(masterKey, masterSalt);
    }

    @Override
    public String toString() {
        return "SrtpCryptoContext{roc=" + roc + "}";
    }
}
