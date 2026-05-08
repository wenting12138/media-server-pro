package com.wenting.mediaserver.core.codec.rtp;

/**
 * Parsed RTP fixed header and derived offsets.
 */
public final class RtpPacketHeader {

    private final int version;
    private final boolean padding;
    private final boolean extension;
    private final int csrcCount;
    private final boolean marker;
    private final int payloadType;
    private final int sequenceNumber;
    private final long timestamp;
    private final long ssrc;
    private final int headerLength;
    private final int payloadOffset;
    private final int payloadLength;

    public RtpPacketHeader(
            int version,
            boolean padding,
            boolean extension,
            int csrcCount,
            boolean marker,
            int payloadType,
            int sequenceNumber,
            long timestamp,
            long ssrc,
            int headerLength,
            int payloadOffset,
            int payloadLength
    ) {
        this.version = version;
        this.padding = padding;
        this.extension = extension;
        this.csrcCount = csrcCount;
        this.marker = marker;
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.ssrc = ssrc;
        this.headerLength = headerLength;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    public int version() {
        return version;
    }

    public boolean padding() {
        return padding;
    }

    public boolean extension() {
        return extension;
    }

    public int csrcCount() {
        return csrcCount;
    }

    public boolean marker() {
        return marker;
    }

    public int payloadType() {
        return payloadType;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    public long timestamp() {
        return timestamp;
    }

    public long ssrc() {
        return ssrc;
    }

    public int headerLength() {
        return headerLength;
    }

    public int payloadOffset() {
        return payloadOffset;
    }

    public int payloadLength() {
        return payloadLength;
    }
}
