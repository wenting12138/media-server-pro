package com.wenting.mediaserver.core.codec.rtcp;

import java.util.Collections;
import java.util.List;

/**
 * Parsed RTCP Receiver Report packet.
 */
public final class RtcpReceiverReportPacket implements RtcpPacket {

    private final long senderSsrc;
    private final List<RtcpReportBlock> reportBlocks;

    public RtcpReceiverReportPacket(long senderSsrc, List<RtcpReportBlock> reportBlocks) {
        this.senderSsrc = senderSsrc;
        this.reportBlocks = reportBlocks == null ? Collections.<RtcpReportBlock>emptyList() : Collections.unmodifiableList(reportBlocks);
    }

    @Override
    public int packetType() {
        return 201;
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public List<RtcpReportBlock> reportBlocks() {
        return reportBlocks;
    }
}
