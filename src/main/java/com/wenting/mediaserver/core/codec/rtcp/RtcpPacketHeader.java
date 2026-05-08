package com.wenting.mediaserver.core.codec.rtcp;

/**
 * Parsed RTCP common header.
 */
public final class RtcpPacketHeader {

    private final int version;
    private final boolean padding;
    private final int reportCount;
    private final int packetType;
    private final int length;
    private final int packetLength;
    private final long senderSsrc;

    public RtcpPacketHeader(
            int version,
            boolean padding,
            int reportCount,
            int packetType,
            int length,
            int packetLength,
            long senderSsrc
    ) {
        this.version = version;
        this.padding = padding;
        this.reportCount = reportCount;
        this.packetType = packetType;
        this.length = length;
        this.packetLength = packetLength;
        this.senderSsrc = senderSsrc;
    }

    public int version() {
        return version;
    }

    public boolean padding() {
        return padding;
    }

    public int reportCount() {
        return reportCount;
    }

    public int packetType() {
        return packetType;
    }

    public int length() {
        return length;
    }

    public int packetLength() {
        return packetLength;
    }

    public long senderSsrc() {
        return senderSsrc;
    }
}
