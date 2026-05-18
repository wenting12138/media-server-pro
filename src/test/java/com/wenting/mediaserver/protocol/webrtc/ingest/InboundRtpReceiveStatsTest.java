package com.wenting.mediaserver.protocol.webrtc.ingest;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundRtpReceiveStatsTest {

    @Test
    void shouldBuildReceiverReportBlockAfterInboundPackets() {
        InboundRtpReceiveStats stats = new InboundRtpReceiveStats(0x11223344L);

        stats.onRtpPacket(packet(1000, 3000L, 0x11223344L), 90000, 1000L);
        stats.onRtpPacket(packet(1001, 6000L, 0x11223344L), 90000, 1033L);
        stats.onRtpPacket(packet(1002, 9000L, 0x11223344L), 90000, 1066L);

        RtcpReportBlock block = stats.snapshotReportBlock(1200L);

        assertEquals(0x11223344L, block.ssrc());
        assertEquals(1002L, block.extendedHighestSequenceNumber());
        assertEquals(0, block.cumulativeLost());
        assertEquals(0, block.fractionLost());
        assertTrue(block.interarrivalJitter() >= 0L);
    }

    @Test
    void shouldIncludeLastSenderReportAndDelay() {
        InboundRtpReceiveStats stats = new InboundRtpReceiveStats(0x11223344L);
        stats.onRtpPacket(packet(1000, 3000L, 0x11223344L), 90000, 1000L);

        RtcpSenderReportPacket senderReport = new RtcpSenderReportPacket(
                0x11223344L,
                0x0000AAAAL,
                0xBBBB0000L,
                3000L,
                1L,
                1200L,
                Collections.emptyList()
        );
        stats.onSenderReport(senderReport, 2000L);

        RtcpReportBlock block = stats.snapshotReportBlock(2600L);

        assertEquals(senderReport.compactNtpTimestamp(), block.lastSenderReport());
        assertEquals((600L * 65536L) / 1000L, block.delaySinceLastSenderReport());
    }

    private static RtpPacket packet(int sequenceNumber, long timestamp, long ssrc) {
        return new RtpPacket(
                2,
                false,
                false,
                0,
                true,
                96,
                sequenceNumber,
                timestamp,
                ssrc,
                null,
                new byte[]{0x01, 0x02, 0x03}
        );
    }
}
