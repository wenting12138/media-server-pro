package com.wenting.mediaserver.core.publish.report;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small per-track RTP reorder buffer for short-distance UDP packet reordering.
 */
public final class RtpReorderBuffer {

    private final int maxOutOfOrderPackets;
    private final int maxBufferedPackets;
    private final Map<Integer, InboundRtpPacket> bufferedPacketsBySequence = new HashMap<Integer, InboundRtpPacket>();
    private Integer expectedSequenceNumber;

    public RtpReorderBuffer(int maxOutOfOrderPackets, int maxBufferedPackets) {
        this.maxOutOfOrderPackets = Math.max(maxOutOfOrderPackets, 1);
        this.maxBufferedPackets = Math.max(maxBufferedPackets, this.maxOutOfOrderPackets);
    }

    public RtpReorderResult offer(InboundRtpPacket packet, RtpPacketHeader header) {
        if (packet == null || header == null) {
            return new RtpReorderResult(null, 0, 0, 0);
        }
        int sequenceNumber = header.sequenceNumber() & 0xFFFF;
        if (expectedSequenceNumber == null) {
            return deliverNow(sequenceNumber, packet, 0);
        }
        if (sequenceNumber == expectedSequenceNumber.intValue()) {
            return deliverNow(sequenceNumber, packet, 0);
        }
        if (RtpSequenceHelper.isOlder(sequenceNumber, expectedSequenceNumber.intValue())) {
            return new RtpReorderResult(null, 1, 0, 0);
        }

        int gap = RtpSequenceHelper.forwardDistance(expectedSequenceNumber.intValue(), sequenceNumber);
        if (gap <= maxOutOfOrderPackets && bufferedPacketsBySequence.size() < maxBufferedPackets) {
            bufferedPacketsBySequence.put(Integer.valueOf(sequenceNumber), packet);
            return new RtpReorderResult(null, 0, 0, 0);
        }
        return deliverNow(sequenceNumber, packet, gap);
    }

    private RtpReorderResult deliverNow(int sequenceNumber, InboundRtpPacket packet, int gapSkippedPackets) {
        List<InboundRtpPacket> orderedPackets = new ArrayList<InboundRtpPacket>();
        orderedPackets.add(packet);
        int reorderedReleasedPackets = 0;
        expectedSequenceNumber = Integer.valueOf(RtpSequenceHelper.next(sequenceNumber));
        while (true) {
            InboundRtpPacket nextPacket = bufferedPacketsBySequence.remove(expectedSequenceNumber);
            if (nextPacket == null) {
                break;
            }
            orderedPackets.add(nextPacket);
            reorderedReleasedPackets++;
            expectedSequenceNumber = Integer.valueOf(RtpSequenceHelper.next(expectedSequenceNumber.intValue()));
        }
        return new RtpReorderResult(orderedPackets, 0, gapSkippedPackets, reorderedReleasedPackets);
    }
}
