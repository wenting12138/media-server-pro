package com.wenting.mediaserver.core.publish.report;

import com.wenting.mediaserver.core.publish.InboundRtpPacket;

import java.util.Collections;
import java.util.List;

/**
 * Result of feeding one RTP packet into a reorder buffer.
 */
public final class RtpReorderResult {

    private final List<InboundRtpPacket> orderedPackets;
    private final int lateDroppedPackets;
    private final int gapSkippedPackets;
    private final int reorderedReleasedPackets;

    public RtpReorderResult(
            List<InboundRtpPacket> orderedPackets,
            int lateDroppedPackets,
            int gapSkippedPackets,
            int reorderedReleasedPackets
    ) {
        this.orderedPackets = orderedPackets == null ? Collections.<InboundRtpPacket>emptyList() : Collections.unmodifiableList(orderedPackets);
        this.lateDroppedPackets = Math.max(lateDroppedPackets, 0);
        this.gapSkippedPackets = Math.max(gapSkippedPackets, 0);
        this.reorderedReleasedPackets = Math.max(reorderedReleasedPackets, 0);
    }

    public List<InboundRtpPacket> orderedPackets() {
        return orderedPackets;
    }

    public int lateDroppedPackets() {
        return lateDroppedPackets;
    }

    public int gapSkippedPackets() {
        return gapSkippedPackets;
    }

    public int reorderedReleasedPackets() {
        return reorderedReleasedPackets;
    }
}
