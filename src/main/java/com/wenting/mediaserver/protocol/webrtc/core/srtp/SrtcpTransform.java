package com.wenting.mediaserver.protocol.webrtc.core.srtp;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Packet-level SRTCP transform for DTLS-SRTP protected RTCP packets.
 */
public final class SrtcpTransform {

    private static final int RTCP_HEADER_AND_SSRC_LENGTH = 8;
    private static final int SRTCP_INDEX_LENGTH = 4;
    private static final int SRTCP_ENCRYPTED_FLAG = 0x80000000;

    private final SrtpCryptoContext cryptoContext;
    private final AtomicInteger sendIndex = new AtomicInteger(0);

    public SrtcpTransform(SrtpCryptoContext cryptoContext) {
        if (cryptoContext == null) {
            throw new IllegalArgumentException("cryptoContext must not be null");
        }
        this.cryptoContext = cryptoContext;
    }

    public byte[] protect(byte[] rtcpPacket) {
        if (rtcpPacket == null || rtcpPacket.length < RTCP_HEADER_AND_SSRC_LENGTH) {
            throw new IllegalArgumentException("RTCP packet too short");
        }
        long senderSsrc = readUnsignedInt(rtcpPacket, 4);
        int index = sendIndex.getAndIncrement() & 0x7FFFFFFF;
        int indexWithFlag = index | SRTCP_ENCRYPTED_FLAG;

        byte[] body = Arrays.copyOfRange(rtcpPacket, RTCP_HEADER_AND_SSRC_LENGTH, rtcpPacket.length);
        byte[] encryptedBody = cryptoContext.encryptRtcp(senderSsrc, index, body);

        byte[] encryptedPacket = new byte[RTCP_HEADER_AND_SSRC_LENGTH + encryptedBody.length];
        System.arraycopy(rtcpPacket, 0, encryptedPacket, 0, RTCP_HEADER_AND_SSRC_LENGTH);
        System.arraycopy(encryptedBody, 0, encryptedPacket, RTCP_HEADER_AND_SSRC_LENGTH, encryptedBody.length);

        byte[] authTag = cryptoContext.authenticateRtcp(encryptedPacket, indexWithFlag);
        byte[] result = new byte[encryptedPacket.length + SRTCP_INDEX_LENGTH + authTag.length];
        System.arraycopy(encryptedPacket, 0, result, 0, encryptedPacket.length);
        writeInt(result, encryptedPacket.length, indexWithFlag);
        System.arraycopy(authTag, 0, result, encryptedPacket.length + SRTCP_INDEX_LENGTH, authTag.length);
        return result;
    }

    public byte[] unprotect(byte[] srtcpPacket) throws SrtpException {
        if (srtcpPacket == null
                || srtcpPacket.length < RTCP_HEADER_AND_SSRC_LENGTH + SRTCP_INDEX_LENGTH + SrtpCryptoContext.AUTH_TAG_LENGTH) {
            throw new SrtpException("SRTCP data too short: " + (srtcpPacket != null ? srtcpPacket.length : 0));
        }
        int authTagOffset = srtcpPacket.length - SrtpCryptoContext.AUTH_TAG_LENGTH;
        int indexOffset = authTagOffset - SRTCP_INDEX_LENGTH;
        if (indexOffset < RTCP_HEADER_AND_SSRC_LENGTH) {
            throw new SrtpException("SRTCP packet missing index");
        }

        int indexWithFlag = readInt(srtcpPacket, indexOffset);
        int index = indexWithFlag & 0x7FFFFFFF;
        boolean encrypted = (indexWithFlag & SRTCP_ENCRYPTED_FLAG) != 0;

        byte[] authTag = Arrays.copyOfRange(srtcpPacket, authTagOffset, srtcpPacket.length);
        byte[] packetData = Arrays.copyOfRange(srtcpPacket, 0, indexOffset);
        if (!cryptoContext.verifyRtcpAuthTag(packetData, indexWithFlag, authTag)) {
            throw new SrtpException("SRTCP auth tag mismatch index=" + index);
        }

        long senderSsrc = readUnsignedInt(packetData, 4);
        byte[] body = Arrays.copyOfRange(packetData, RTCP_HEADER_AND_SSRC_LENGTH, packetData.length);
        byte[] plainBody = encrypted ? cryptoContext.decryptRtcp(senderSsrc, index, body) : body;
        byte[] result = new byte[RTCP_HEADER_AND_SSRC_LENGTH + plainBody.length];
        System.arraycopy(packetData, 0, result, 0, RTCP_HEADER_AND_SSRC_LENGTH);
        System.arraycopy(plainBody, 0, result, RTCP_HEADER_AND_SSRC_LENGTH, plainBody.length);
        return result;
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (long) (data[offset + 3] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }
}
