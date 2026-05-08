package com.wenting.mediaserver.core.codec.rtcp;

import java.util.Collections;
import java.util.List;

/**
 * Parsed RTCP Sender Report packet.
 */
public final class RtcpSenderReportPacket implements RtcpPacket {

    private final long senderSsrc;
    private final long ntpTimestampMostSignificant;
    private final long ntpTimestampLeastSignificant;
    private final long rtpTimestamp;
    private final long senderPacketCount;
    private final long senderOctetCount;
    private final List<RtcpReportBlock> reportBlocks;

    public RtcpSenderReportPacket(
            long senderSsrc,
            long ntpTimestampMostSignificant,
            long ntpTimestampLeastSignificant,
            long rtpTimestamp,
            long senderPacketCount,
            long senderOctetCount,
            List<RtcpReportBlock> reportBlocks
    ) {
        this.senderSsrc = senderSsrc;
        this.ntpTimestampMostSignificant = ntpTimestampMostSignificant;
        this.ntpTimestampLeastSignificant = ntpTimestampLeastSignificant;
        this.rtpTimestamp = rtpTimestamp;
        this.senderPacketCount = senderPacketCount;
        this.senderOctetCount = senderOctetCount;
        this.reportBlocks = reportBlocks == null ? Collections.<RtcpReportBlock>emptyList() : Collections.unmodifiableList(reportBlocks);
    }

    @Override
    public int packetType() {
        return 200;
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public long ntpTimestampMostSignificant() {
        return ntpTimestampMostSignificant;
    }

    public long ntpTimestampLeastSignificant() {
        return ntpTimestampLeastSignificant;
    }

    public long rtpTimestamp() {
        return rtpTimestamp;
    }

    public long senderPacketCount() {
        return senderPacketCount;
    }

    public long senderOctetCount() {
        return senderOctetCount;
    }

    public List<RtcpReportBlock> reportBlocks() {
        return reportBlocks;
    }

    public long compactNtpTimestamp() {
        return ((ntpTimestampMostSignificant & 0xFFFFL) << 16)
                | ((ntpTimestampLeastSignificant >>> 16) & 0xFFFFL);
    }

    public long ntpTimestampMillis() {
        long seconds = ntpTimestampMostSignificant & 0xFFFFFFFFL;
        long fraction = ntpTimestampLeastSignificant & 0xFFFFFFFFL;
        long unixSeconds = seconds - 2208988800L;
        long millisFraction = (fraction * 1000L) >>> 32;
        return (unixSeconds * 1000L) + millisFraction;
    }
}
