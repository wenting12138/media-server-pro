package com.wenting.mediaserver.core.publish.report;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;

/**
 * Aggregated RTCP statistics for one published track.
 */
public final class RtcpTrackStats {

    private final String trackId;
    private long senderSsrc;
    private long senderReportNtpMillis;
    private long senderReportCompactNtp;
    private long senderReportRtpTimestamp;
    private long senderPacketCount;
    private long senderOctetCount;
    private long senderReportReceivedAtMillis;
    private int fractionLost;
    private int cumulativeLost;
    private long highestSequenceNumber;
    private long interarrivalJitter;
    private long lastSenderReport;
    private long delaySinceLastSenderReport;
    private Long roundTripTimeMillis;

    public RtcpTrackStats(String trackId) {
        this.trackId = trackId == null ? "" : trackId;
    }

    public String trackId() {
        return trackId;
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public long senderReportNtpMillis() {
        return senderReportNtpMillis;
    }

    public long senderReportCompactNtp() {
        return senderReportCompactNtp;
    }

    public long senderReportRtpTimestamp() {
        return senderReportRtpTimestamp;
    }

    public long senderPacketCount() {
        return senderPacketCount;
    }

    public long senderOctetCount() {
        return senderOctetCount;
    }

    public long senderReportReceivedAtMillis() {
        return senderReportReceivedAtMillis;
    }

    public int fractionLost() {
        return fractionLost;
    }

    public int cumulativeLost() {
        return cumulativeLost;
    }

    public long highestSequenceNumber() {
        return highestSequenceNumber;
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

    public Long roundTripTimeMillis() {
        return roundTripTimeMillis;
    }

    public Long mapRtpTimestampToNtpMillis(long rtpTimestamp, int clockRate) {
        if (clockRate <= 0 || senderReportCompactNtp == 0L) {
            return null;
        }
        long delta = (rtpTimestamp - senderReportRtpTimestamp) & 0xFFFFFFFFL;
        long deltaMillis = (delta * 1000L) / clockRate;
        return Long.valueOf(senderReportNtpMillis + deltaMillis);
    }

    public void updateSenderReport(RtcpSenderReportPacket senderReport, long receivedAtMillis) {
        if (senderReport == null) {
            return;
        }
        this.senderSsrc = senderReport.senderSsrc();
        this.senderReportNtpMillis = senderReport.ntpTimestampMillis();
        this.senderReportCompactNtp = senderReport.compactNtpTimestamp();
        this.senderReportRtpTimestamp = senderReport.rtpTimestamp();
        this.senderPacketCount = senderReport.senderPacketCount();
        this.senderOctetCount = senderReport.senderOctetCount();
        this.senderReportReceivedAtMillis = receivedAtMillis;
    }

    public void updateReportBlock(RtcpReportBlock reportBlock, long receivedAtMillis) {
        if (reportBlock == null) {
            return;
        }
        this.fractionLost = reportBlock.fractionLost();
        this.cumulativeLost = reportBlock.cumulativeLost();
        this.highestSequenceNumber = reportBlock.extendedHighestSequenceNumber();
        this.interarrivalJitter = reportBlock.interarrivalJitter();
        this.lastSenderReport = reportBlock.lastSenderReport();
        this.delaySinceLastSenderReport = reportBlock.delaySinceLastSenderReport();
        if (senderReportCompactNtp != 0L && lastSenderReport == senderReportCompactNtp && senderReportReceivedAtMillis > 0L) {
            long delayMillis = (delaySinceLastSenderReport * 1000L) / 65536L;
            long rttMillis = receivedAtMillis - senderReportReceivedAtMillis - delayMillis;
            this.roundTripTimeMillis = Long.valueOf(Math.max(rttMillis, 0L));
        }
    }
}
