package com.wenting.mediaserver.protocol.webrtc.core.rtp;

import java.util.Arrays;

/**
 * RTP packet (RFC 3550).
 *
 * Encodes/decodes RTP header + payload. Supports version=2, CSRC list, padding
 * flag, extension flag, marker bit, and 7-bit payload type.
 *
 * Wire format:
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                           timestamp                           |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                           SSRC                                |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                    CSRC list (0-15 entries)                   |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                    payload bytes ...                           |
 */
public class RtpPacket {

    public static final int FIXED_HEADER_SIZE = 12;
    public static final int MAX_CSRC_COUNT = 15;

    private final int version;
    private final boolean padding;
    private final boolean extension;
    private final int csrcCount;
    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final long[] csrcList;
    private final byte[] payload;

    public RtpPacket(int version, boolean padding, boolean extension,
                     int csrcCount, boolean marker, int payloadType,
                     int sequenceNumber, long timestamp, long ssrc,
                     long[] csrcList, byte[] payload) {
        if (version != 2) {
            throw new IllegalArgumentException("RTP version must be 2, got " + version);
        }
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("PT must be 0-127");
        }
        if (csrcCount < 0 || csrcCount > MAX_CSRC_COUNT) {
            throw new IllegalArgumentException("CSRC count must be 0-15");
        }
        if (csrcList != null && csrcList.length != csrcCount) {
            throw new IllegalArgumentException("CSRC list length must match csrcCount");
        }
        this.version = version;
        this.padding = padding;
        this.extension = extension;
        this.csrcCount = csrcCount;
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber & 0xFFFF;
        this.timestamp = timestamp & 0xFFFFFFFFL;
        this.ssrc = ssrc & 0xFFFFFFFFL;
        this.csrcList = csrcList != null ? Arrays.copyOf(csrcList, csrcList.length) : null;
        this.payload = payload != null ? payload : new byte[0];
    }

    // ---- Decode ----

    /**
     * Decode an RTP packet from wire bytes.
     */
    public static RtpPacket decode(byte[] data) {
        if (data == null || data.length < FIXED_HEADER_SIZE) {
            throw new IllegalArgumentException("RTP data too short: " + (data != null ? data.length : 0));
        }

        int firstByte = data[0] & 0xFF;
        int version = (firstByte >> 6) & 0x03;
        if (version != 2) {
            throw new IllegalArgumentException("RTP version must be 2, got " + version);
        }

        boolean padding = (firstByte & 0x20) != 0;
        boolean extension = (firstByte & 0x10) != 0;
        int csrcCount = firstByte & 0x0F;

        int secondByte = data[1] & 0xFF;
        boolean marker = (secondByte & 0x80) != 0;
        int payloadType = secondByte & 0x7F;

        int sequenceNumber = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long timestamp = ((long)(data[4] & 0xFF) << 24)
                       | ((long)(data[5] & 0xFF) << 16)
                       | ((long)(data[6] & 0xFF) << 8)
                       | ((long)(data[7] & 0xFF));
        long ssrc = ((long)(data[8] & 0xFF) << 24)
                  | ((long)(data[9] & 0xFF) << 16)
                  | ((long)(data[10] & 0xFF) << 8)
                  | ((long)(data[11] & 0xFF));

        // CSRC list
        long[] csrcList = null;
        if (csrcCount > 0) {
            csrcList = new long[csrcCount];
            int offset = FIXED_HEADER_SIZE;
            for (int i = 0; i < csrcCount; i++) {
                csrcList[i] = ((long)(data[offset] & 0xFF) << 24)
                            | ((long)(data[offset + 1] & 0xFF) << 16)
                            | ((long)(data[offset + 2] & 0xFF) << 8)
                            | ((long)(data[offset + 3] & 0xFF));
                offset += 4;
            }
        }

        int headerLen = FIXED_HEADER_SIZE + csrcCount * 4;
        int payloadLen = data.length - headerLen;

        // Handle RTP padding: last byte indicates padding length
        if (padding && payloadLen > 0) {
            int padLen = data[data.length - 1] & 0xFF;
            if (padLen > 0 && padLen <= payloadLen) {
                payloadLen -= padLen;
            }
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, headerLen, payload, 0, payloadLen);

        return new RtpPacket(version, padding, extension, csrcCount,
            marker, payloadType, sequenceNumber, timestamp, ssrc,
            csrcList, payload);
    }

    // ---- Encode ----

    /**
     * Encode the RTP header only (fixed 12 bytes + CSRC list).
     * Used for SRTP authentication tag computation.
     */
    public byte[] encodeHeader() {
        int headerLen = FIXED_HEADER_SIZE + (csrcList != null ? csrcList.length * 4 : 0);
        byte[] buf = new byte[headerLen];
        fillHeader(buf);
        return buf;
    }

    /**
     * Encode the full RTP packet (header + payload) to wire bytes.
     */
    public byte[] encode() {
        int headerLen = FIXED_HEADER_SIZE + (csrcList != null ? csrcList.length * 4 : 0);
        int totalLen = headerLen + payload.length;
        byte[] buf = new byte[totalLen];
        fillHeader(buf);
        System.arraycopy(payload, 0, buf, headerLen, payload.length);
        return buf;
    }

    private void fillHeader(byte[] buf) {
        buf[0] = (byte) ((version << 6)
                       | (padding ? 0x20 : 0)
                       | (extension ? 0x10 : 0)
                       | (csrcCount & 0x0F));
        buf[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));
        buf[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        buf[3] = (byte) (sequenceNumber & 0xFF);
        buf[4] = (byte) ((timestamp >> 24) & 0xFF);
        buf[5] = (byte) ((timestamp >> 16) & 0xFF);
        buf[6] = (byte) ((timestamp >> 8) & 0xFF);
        buf[7] = (byte) (timestamp & 0xFF);
        buf[8] = (byte) ((ssrc >> 24) & 0xFF);
        buf[9] = (byte) ((ssrc >> 16) & 0xFF);
        buf[10] = (byte) ((ssrc >> 8) & 0xFF);
        buf[11] = (byte) (ssrc & 0xFF);

        // CSRC list
        if (csrcList != null) {
            int offset = FIXED_HEADER_SIZE;
            for (long csrc : csrcList) {
                buf[offset] = (byte) ((csrc >> 24) & 0xFF);
                buf[offset + 1] = (byte) ((csrc >> 16) & 0xFF);
                buf[offset + 2] = (byte) ((csrc >> 8) & 0xFF);
                buf[offset + 3] = (byte) (csrc & 0xFF);
                offset += 4;
            }
        }
    }

    // ---- Getters ----

    public int getVersion() { return version; }
    public boolean hasPadding() { return padding; }
    public boolean hasExtension() { return extension; }
    public int getCsrcCount() { return csrcCount; }
    public boolean getMarker() { return marker; }
    public int getPayloadType() { return payloadType; }
    public int getSequenceNumber() { return sequenceNumber; }
    public long getTimestamp() { return timestamp; }
    public long getSsrc() { return ssrc; }
    public long[] getCsrcList() { return csrcList != null ? Arrays.copyOf(csrcList, csrcList.length) : null; }
    public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RtpPacket)) return false;
        RtpPacket that = (RtpPacket) o;
        return version == that.version
            && padding == that.padding
            && extension == that.extension
            && csrcCount == that.csrcCount
            && marker == that.marker
            && payloadType == that.payloadType
            && sequenceNumber == that.sequenceNumber
            && timestamp == that.timestamp
            && ssrc == that.ssrc
            && Arrays.equals(payload, that.payload)
            && Arrays.equals(csrcList, that.csrcList);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(payload);
        result = 31 * result + Arrays.hashCode(csrcList);
        return result;
    }

    @Override
    public String toString() {
        return "RtpPacket{pt=" + payloadType + " seq=" + sequenceNumber
            + " ts=" + timestamp + " ssrc=" + ssrc + " len=" + payload.length + "}";
    }
}
