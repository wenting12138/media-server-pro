package com.wenting.mediaserver.core.codec.rtcp;

/**
 * One RTCP reception report block.
 */
public final class RtcpReportBlock {

    private final long ssrc;
    private final int fractionLost;
    private final int cumulativeLost;
    private final long extendedHighestSequenceNumber;
    private final long interarrivalJitter;
    private final long lastSenderReport;
    private final long delaySinceLastSenderReport;

    public RtcpReportBlock(
            long ssrc,
            int fractionLost,
            int cumulativeLost,
            long extendedHighestSequenceNumber,
            long interarrivalJitter,
            long lastSenderReport,
            long delaySinceLastSenderReport
    ) {
        this.ssrc = ssrc;
        this.fractionLost = fractionLost;
        this.cumulativeLost = cumulativeLost;
        this.extendedHighestSequenceNumber = extendedHighestSequenceNumber;
        this.interarrivalJitter = interarrivalJitter;
        this.lastSenderReport = lastSenderReport;
        this.delaySinceLastSenderReport = delaySinceLastSenderReport;
    }

    public long ssrc() {
        return ssrc;
    }

    public int fractionLost() {
        return fractionLost;
    }

    public int cumulativeLost() {
        return cumulativeLost;
    }

    public long extendedHighestSequenceNumber() {
        return extendedHighestSequenceNumber;
    }

    public long interarrivalJitter() {
        return interarrivalJitter;
    }

    public long lastSenderReport() {
        return lastSenderReport;
    }

    public long delaySinceLastSenderReport() {
        return delaySinceLastSenderReport;
    }
}
