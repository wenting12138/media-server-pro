package com.wenting.mediaserver.protocol.webrtc.ingest;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;

/**
 * Minimal RTP receive-side statistics for generating RTCP Receiver Reports.
 */
public final class InboundRtpReceiveStats {

    private final long mediaSsrc;
    private boolean initialized;
    private int baseSequenceNumber;
    private int highestSequenceNumber;
    private long sequenceCycles;
    private long receivedPacketCount;
    private long expectedPrior;
    private long receivedPrior;
    private long jitter;
    private long previousTransit = Long.MIN_VALUE;
    private long lastSenderReportCompactNtp;
    private long lastSenderReportReceivedAtMs;

    public InboundRtpReceiveStats(long mediaSsrc) {
        this.mediaSsrc = mediaSsrc & 0xFFFFFFFFL;
    }

    public long mediaSsrc() {
        return mediaSsrc;
    }

    public synchronized boolean hasReceivedPackets() {
        return receivedPacketCount > 0L;
    }

    public synchronized void onRtpPacket(RtpPacket packet, int clockRate, long arrivalTimeMs) {
        if (packet == null) {
            return;
        }
        int sequenceNumber = packet.getSequenceNumber() & 0xFFFF;
        if (!initialized) {
            initialized = true;
            baseSequenceNumber = sequenceNumber;
            highestSequenceNumber = sequenceNumber;
        } else {
            int delta = sequenceNumber - highestSequenceNumber;
            if (delta > 0 && delta < 32768) {
                highestSequenceNumber = sequenceNumber;
            } else if (delta < -32768) {
                sequenceCycles += 0x10000L;
                highestSequenceNumber = sequenceNumber;
            }
        }
        receivedPacketCount++;

        if (clockRate > 0) {
            long arrivalRtpUnits = (arrivalTimeMs * (long) clockRate) / 1000L;
            long transit = arrivalRtpUnits - (packet.getTimestamp() & 0xFFFFFFFFL);
            if (previousTransit != Long.MIN_VALUE) {
                long d = Math.abs(transit - previousTransit);
                jitter += (d - jitter) / 16L;
            }
            previousTransit = transit;
        }
    }

    public synchronized void onSenderReport(RtcpSenderReportPacket senderReport, long receivedAtMs) {
        if (senderReport == null || (senderReport.senderSsrc() & 0xFFFFFFFFL) != mediaSsrc) {
            return;
        }
        lastSenderReportCompactNtp = senderReport.compactNtpTimestamp();
        lastSenderReportReceivedAtMs = receivedAtMs;
    }

    public synchronized RtcpReportBlock snapshotReportBlock(long nowMs) {
        if (!initialized) {
            return new RtcpReportBlock(mediaSsrc, 0, 0, 0L, 0L, 0L, 0L);
        }
        long extendedHighestSequence = sequenceCycles + (highestSequenceNumber & 0xFFFFL);
        long expected = extendedHighestSequence - (baseSequenceNumber & 0xFFFFL) + 1L;
        long cumulativeLostLong = expected - receivedPacketCount;
        int cumulativeLost = clampSigned24(cumulativeLostLong);

        long expectedInterval = expected - expectedPrior;
        long receivedInterval = receivedPacketCount - receivedPrior;
        long lostInterval = expectedInterval - receivedInterval;
        int fractionLost = 0;
        if (expectedInterval > 0L && lostInterval > 0L) {
            fractionLost = (int) Math.min(255L, (lostInterval << 8) / expectedInterval);
        }
        expectedPrior = expected;
        receivedPrior = receivedPacketCount;

        long dlsr = 0L;
        if (lastSenderReportCompactNtp != 0L && lastSenderReportReceivedAtMs > 0L && nowMs >= lastSenderReportReceivedAtMs) {
            dlsr = ((nowMs - lastSenderReportReceivedAtMs) * 65536L) / 1000L;
        }

        return new RtcpReportBlock(
                mediaSsrc,
                fractionLost,
                cumulativeLost,
                extendedHighestSequence & 0xFFFFFFFFL,
                jitter & 0xFFFFFFFFL,
                lastSenderReportCompactNtp & 0xFFFFFFFFL,
                dlsr & 0xFFFFFFFFL
        );
    }

    private static int clampSigned24(long value) {
        if (value > 0x7FFFFFL) {
            return 0x7FFFFF;
        }
        if (value < -0x800000L) {
            return -0x800000;
        }
        return (int) value;
    }
}
