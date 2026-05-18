package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.core.codec.rtcp.RtcpGenericNackPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReceiverReportPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.protocol.webrtc.util.ReportPacketUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RTCPeerConnectionReceiverReportTest {

    @Test
    void shouldEncodeReceiverReportPacket() {
        RtcpReportBlock reportBlock = new RtcpReportBlock(
                0x11223344L,
                12,
                34,
                0x01020304L,
                0x05060708L,
                0x090A0B0CL,
                0x0D0E0F10L
        );

        byte[] packet = ReportPacketUtil.encodeReceiverReportPacket(
                0x55667788L,
                Collections.singletonList(reportBlock)
        );

        RtpParseResult parseResult = new RtpPacketParser().parse(packet);
        assertTrue(parseResult.rtcp());
        assertTrue(parseResult.rtcpPacket() instanceof RtcpReceiverReportPacket);

        RtcpReceiverReportPacket receiverReport = (RtcpReceiverReportPacket) parseResult.rtcpPacket();
        assertEquals(0x55667788L, receiverReport.senderSsrc());
        assertEquals(1, receiverReport.reportBlocks().size());
        RtcpReportBlock parsedBlock = receiverReport.reportBlocks().get(0);
        assertEquals(reportBlock.ssrc(), parsedBlock.ssrc());
        assertEquals(reportBlock.fractionLost(), parsedBlock.fractionLost());
        assertEquals(reportBlock.cumulativeLost(), parsedBlock.cumulativeLost());
        assertEquals(reportBlock.extendedHighestSequenceNumber(), parsedBlock.extendedHighestSequenceNumber());
        assertEquals(reportBlock.interarrivalJitter(), parsedBlock.interarrivalJitter());
        assertEquals(reportBlock.lastSenderReport(), parsedBlock.lastSenderReport());
        assertEquals(reportBlock.delaySinceLastSenderReport(), parsedBlock.delaySinceLastSenderReport());
    }

    @Test
    void shouldEncodeGenericNackPacket() {
        byte[] packet = ReportPacketUtil.encodeGenericNackPacket(
                0x55667788L,
                0x11223344L,
                Arrays.asList(1001, 1002, 1005, 1006, 1019)
        );

        RtpParseResult parseResult = new RtpPacketParser().parse(packet);
        assertTrue(parseResult.rtcp());
        assertTrue(parseResult.rtcpPacket() instanceof RtcpGenericNackPacket);

        RtcpGenericNackPacket nackPacket = (RtcpGenericNackPacket) parseResult.rtcpPacket();
        assertEquals(0x55667788L, nackPacket.senderSsrc());
        assertEquals(0x11223344L, nackPacket.mediaSsrc());
        assertEquals(Arrays.asList(1001, 1002, 1005, 1006, 1019), nackPacket.lostSequenceNumbers());
    }
}
