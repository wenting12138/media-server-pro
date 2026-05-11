package com.wenting.mediaserver.protocol.webrtc.srtp;

import com.wenting.mediaserver.protocol.webrtc.dtls.SrtpKeyingMaterial;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal SRTP send-side protector for AES_CM_128_HMAC_SHA1_80.
 */
public final class SrtpPacketEncoder {

    private static final int RTP_FIXED_HEADER_LENGTH = 12;
    private static final int AUTH_TAG_LENGTH = 10;
    private static final int AUTH_KEY_LENGTH = 20;
    private static final byte LABEL_RTP_ENCRYPTION = 0x00;
    private static final byte LABEL_RTP_AUTHENTICATION = 0x01;
    private static final byte LABEL_RTP_SALT = 0x02;

    private final Map<Long, SrtpSenderState> senderStatesBySsrc = new ConcurrentHashMap<Long, SrtpSenderState>();

    public byte[] protectRtp(byte[] rtpPacket, SrtpKeyingMaterial keyingMaterial) {
        if (rtpPacket == null || rtpPacket.length < RTP_FIXED_HEADER_LENGTH || keyingMaterial == null) {
            return new byte[0];
        }
        try {
            byte[] masterKey = keyingMaterial.serverWriteKey();
            byte[] masterSalt = keyingMaterial.serverWriteSalt();
            if (masterKey.length != SrtpKeyingMaterial.KEY_LENGTH || masterSalt.length != SrtpKeyingMaterial.SALT_LENGTH) {
                return new byte[0];
            }

            byte[] sessionEncryptionKey = deriveSessionMaterial(masterKey, masterSalt, LABEL_RTP_ENCRYPTION, SrtpKeyingMaterial.KEY_LENGTH);
            byte[] sessionAuthenticationKey = deriveSessionMaterial(masterKey, masterSalt, LABEL_RTP_AUTHENTICATION, AUTH_KEY_LENGTH);
            byte[] sessionSaltKey = deriveSessionMaterial(masterKey, masterSalt, LABEL_RTP_SALT, SrtpKeyingMaterial.SALT_LENGTH);

            int sequenceNumber = readUnsignedShort(rtpPacket, 2);
            long ssrc = readUnsignedInt(rtpPacket, 8);
            long packetIndex = senderState(ssrc).nextPacketIndex(sequenceNumber);

            byte[] protectedPacket = Arrays.copyOf(rtpPacket, rtpPacket.length + AUTH_TAG_LENGTH);
            byte[] payloadCiphertext = encryptPayload(
                    Arrays.copyOfRange(rtpPacket, RTP_FIXED_HEADER_LENGTH, rtpPacket.length),
                    sessionEncryptionKey,
                    buildPacketIv(sessionSaltKey, ssrc, packetIndex)
            );
            System.arraycopy(payloadCiphertext, 0, protectedPacket, RTP_FIXED_HEADER_LENGTH, payloadCiphertext.length);

            byte[] authTag = computeAuthTag(protectedPacket, rtpPacket.length, sessionAuthenticationKey, packetIndex);
            System.arraycopy(authTag, 0, protectedPacket, rtpPacket.length, AUTH_TAG_LENGTH);
            return protectedPacket;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to protect SRTP packet", e);
        }
    }

    private SrtpSenderState senderState(long ssrc) {
        Long key = Long.valueOf(ssrc);
        SrtpSenderState existing = senderStatesBySsrc.get(key);
        if (existing != null) {
            return existing;
        }
        SrtpSenderState created = new SrtpSenderState();
        SrtpSenderState previous = senderStatesBySsrc.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    private byte[] deriveSessionMaterial(byte[] masterKey, byte[] masterSalt, byte label, int length) throws Exception {
        byte[] iv = new byte[16];
        System.arraycopy(masterSalt, 0, iv, 0, Math.min(masterSalt.length, 14));
        iv[7] ^= label;
        return aesCtr(masterKey, iv, length);
    }

    private byte[] buildPacketIv(byte[] sessionSaltKey, long ssrc, long packetIndex) {
        byte[] iv = new byte[16];
        System.arraycopy(sessionSaltKey, 0, iv, 0, Math.min(sessionSaltKey.length, 14));
        xorUnsignedInt(iv, 4, ssrc);
        xor48(iv, 8, packetIndex & 0xFFFFFFFFFFFFL);
        return iv;
    }

    private byte[] encryptPayload(byte[] payload, byte[] sessionEncryptionKey, byte[] iv) throws Exception {
        if (payload.length == 0) {
            return payload;
        }
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionEncryptionKey, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(payload);
    }

    private byte[] aesCtr(byte[] key, byte[] iv, int length) throws Exception {
        byte[] zeros = new byte[length];
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(zeros);
    }

    private byte[] computeAuthTag(byte[] packetWithSpace, int packetLength, byte[] sessionAuthenticationKey, long packetIndex) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sessionAuthenticationKey, "HmacSHA1"));
        mac.update(packetWithSpace, 0, packetLength);
        mac.update(new byte[]{
                (byte) ((packetIndex >> 40) & 0xFF),
                (byte) ((packetIndex >> 32) & 0xFF),
                (byte) ((packetIndex >> 24) & 0xFF),
                (byte) ((packetIndex >> 16) & 0xFF)
        });
        byte[] fullTag = mac.doFinal();
        return Arrays.copyOf(fullTag, AUTH_TAG_LENGTH);
    }

    private int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private long readUnsignedInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL) << 24)
                | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }

    private void xorUnsignedInt(byte[] iv, int offset, long value) {
        iv[offset] ^= (byte) ((value >> 24) & 0xFF);
        iv[offset + 1] ^= (byte) ((value >> 16) & 0xFF);
        iv[offset + 2] ^= (byte) ((value >> 8) & 0xFF);
        iv[offset + 3] ^= (byte) (value & 0xFF);
    }

    private void xor48(byte[] iv, int offset, long value) {
        iv[offset] ^= (byte) ((value >> 40) & 0xFF);
        iv[offset + 1] ^= (byte) ((value >> 32) & 0xFF);
        iv[offset + 2] ^= (byte) ((value >> 24) & 0xFF);
        iv[offset + 3] ^= (byte) ((value >> 16) & 0xFF);
        iv[offset + 4] ^= (byte) ((value >> 8) & 0xFF);
        iv[offset + 5] ^= (byte) (value & 0xFF);
    }
}
