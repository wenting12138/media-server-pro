package com.wenting.mediaserver.protocol.webrtc.core.srtp;


import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;

/**
 * Packet-level SRTP transform (RFC 3711).
 *
 * Wraps SrtpCryptoContext to provide protect/unprotect at the RTP packet level.
 *
 * protect(RtpPacket) → SRTP bytes: RTP header + encrypted payload + 10-byte auth tag
 * unprotect(byte[]) → RtpPacket: verify auth tag, decrypt payload
 */
public class SrtpTransform {

    private final SrtpCryptoContext cryptoContext;
    private final long ssrc;

    public SrtpTransform(SrtpCryptoContext cryptoContext, long ssrc) {
        if (cryptoContext == null) {
            throw new IllegalArgumentException("cryptoContext must not be null");
        }
        this.cryptoContext = cryptoContext;
        this.ssrc = ssrc & 0xFFFFFFFFL;
    }

    public SrtpCryptoContext getCryptoContext() {
        return cryptoContext;
    }

    public long getSsrc() {
        return ssrc;
    }

    /**
     * Protect an RTP packet into SRTP format.
     *
     * @param packet the plaintext RTP packet
     * @return SRTP bytes: encoded header + encrypted payload + 10-byte auth tag
     */
    public byte[] protect(RtpPacket packet) {
        byte[] header = packet.encodeHeader();
        byte[] plainPayload = packet.getPayload();
        int seq = packet.getSequenceNumber();

        // Encrypt payload
        byte[] encryptedPayload = cryptoContext.encrypt(ssrc, seq, plainPayload);

        // Compute auth tag over header + encrypted payload + ROC
        long roc = cryptoContext.getRoc();
        byte[] authTag = cryptoContext.authenticate(header, encryptedPayload, ssrc, roc);

        // Update ROC
        cryptoContext.updateRoc(seq);

        // Assemble: header + encrypted payload + auth tag
        byte[] result = new byte[header.length + encryptedPayload.length + authTag.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(encryptedPayload, 0, result, header.length, encryptedPayload.length);
        System.arraycopy(authTag, 0, result, header.length + encryptedPayload.length, authTag.length);

        return result;
    }

    /**
     * Unprotect SRTP bytes back into an RtpPacket.
     *
     * @param srtpData the received SRTP bytes
     * @return decrypted RtpPacket
     * @throws SrtpException on auth tag mismatch or invalid data
     */
    public RtpPacket unprotect(byte[] srtpData) throws SrtpException {
        if (srtpData == null || srtpData.length < 12 + SrtpCryptoContext.AUTH_TAG_LENGTH) {
            throw new SrtpException("SRTP data too short: " + (srtpData != null ? srtpData.length : 0));
        }

        int authTagLen = SrtpCryptoContext.AUTH_TAG_LENGTH;

        // Parse the RTP header to determine header length
        int firstByte = srtpData[0] & 0xFF;
        int csrcCount = firstByte & 0x0F;
        int headerLen = 12 + csrcCount * 4;

        int payloadEnd = srtpData.length - authTagLen;

        if (headerLen > payloadEnd) {
            throw new SrtpException("SRTP header exceeds data length");
        }

        byte[] header = new byte[headerLen];
        System.arraycopy(srtpData, 0, header, 0, headerLen);

        byte[] encryptedPayload = new byte[payloadEnd - headerLen];
        System.arraycopy(srtpData, headerLen, encryptedPayload, 0, encryptedPayload.length);

        byte[] receivedAuthTag = new byte[authTagLen];
        System.arraycopy(srtpData, payloadEnd, receivedAuthTag, 0, authTagLen);

        // Extract SSRC and seq from header for verification
        long packetSsrc = ((long)(header[8] & 0xFF) << 24)
                        | ((long)(header[9] & 0xFF) << 16)
                        | ((long)(header[10] & 0xFF) << 8)
                        | ((long)(header[11] & 0xFF));
        int seq = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);

        long roc = cryptoContext.getRoc();

        // Verify auth tag
        boolean valid = cryptoContext.verifyAuthTag(header, encryptedPayload, packetSsrc, roc, receivedAuthTag);
        if (!valid) {
            throw new SrtpException("SRTP auth tag mismatch for SSRC=" + packetSsrc + " seq=" + seq);
        }

        // Decrypt payload
        byte[] decryptedPayload = cryptoContext.decrypt(packetSsrc, seq, encryptedPayload);

        // Update ROC
        cryptoContext.updateRoc(seq);

        // Build the RtpPacket from the header and decrypted payload
        return parseRtpFromHeader(header, decryptedPayload);
    }

    private static RtpPacket parseRtpFromHeader(byte[] header, byte[] decryptedPayload) {
        int firstByte = header[0] & 0xFF;
        int version = (firstByte >> 6) & 0x03;
        boolean padding = (firstByte & 0x20) != 0;
        boolean extension = (firstByte & 0x10) != 0;
        int csrcCount = firstByte & 0x0F;

        int secondByte = header[1] & 0xFF;
        boolean marker = (secondByte & 0x80) != 0;
        int payloadType = secondByte & 0x7F;

        int sequenceNumber = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        long timestamp = ((long)(header[4] & 0xFF) << 24)
                       | ((long)(header[5] & 0xFF) << 16)
                       | ((long)(header[6] & 0xFF) << 8)
                       | ((long)(header[7] & 0xFF));
        long ssrc = ((long)(header[8] & 0xFF) << 24)
                  | ((long)(header[9] & 0xFF) << 16)
                  | ((long)(header[10] & 0xFF) << 8)
                  | ((long)(header[11] & 0xFF));

        long[] csrcList = null;
        if (csrcCount > 0) {
            csrcList = new long[csrcCount];
            int offset = 12;
            for (int i = 0; i < csrcCount; i++) {
                csrcList[i] = ((long)(header[offset] & 0xFF) << 24)
                            | ((long)(header[offset + 1] & 0xFF) << 16)
                            | ((long)(header[offset + 2] & 0xFF) << 8)
                            | ((long)(header[offset + 3] & 0xFF));
                offset += 4;
            }
        }

        return new RtpPacket(version, padding, extension, csrcCount,
            marker, payloadType, sequenceNumber, timestamp, ssrc,
            csrcList, decryptedPayload);
    }
}
