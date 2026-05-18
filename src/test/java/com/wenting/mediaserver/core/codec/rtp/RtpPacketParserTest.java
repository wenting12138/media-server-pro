package com.wenting.mediaserver.core.codec.rtp;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReceiverReportPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpGenericNackPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPictureLossIndicationPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpPacketParserTest {

    private final RtpPacketParser parser = new RtpPacketParser();

    @Test
    void shouldParseBasicRtpPacket() {
        byte[] packet = new byte[]{
                (byte) 0x80,
                (byte) 0xE0,
                0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66, 0x77
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtp());
        assertFalse(result.rtcp());
        assertNotNull(result.rtpHeader());
        assertEquals(2, result.rtpHeader().version());
        assertTrue(result.rtpHeader().marker());
        assertEquals(96, result.rtpHeader().payloadType());
        assertEquals(0x1234, result.rtpHeader().sequenceNumber());
        assertEquals(0x01020304L, result.rtpHeader().timestamp());
        assertEquals(0x11223344L, result.rtpHeader().ssrc());
        assertEquals(12, result.rtpHeader().headerLength());
        assertEquals(12, result.rtpHeader().payloadOffset());
        assertEquals(3, result.rtpHeader().payloadLength());
    }

    @Test
    void shouldParseRtpPacketWithExtensionAndPadding() {
        byte[] packet = new byte[]{
                (byte) 0xB1,
                (byte) 0x60,
                0x00, 0x01,
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x03,
                0x10, 0x20, 0x30, 0x40,
                0x00, 0x01,
                0x00, 0x01,
                0x55, 0x66, 0x77, 0x66,
                0x11, 0x22, 0x33,
                0x00, 0x00, 0x00, 0x04
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtp());
        assertEquals(1, result.rtpHeader().csrcCount());
        assertTrue(result.rtpHeader().extension());
        assertTrue(result.rtpHeader().padding());
        assertEquals(24, result.rtpHeader().headerLength());
        assertEquals(24, result.rtpHeader().payloadOffset());
        assertEquals(3, result.rtpHeader().payloadLength());
    }

    @Test
    void shouldParseBasicRtcpPacket() {
        byte[] packet = new byte[]{
                (byte) 0x80,
                (byte) 200,
                0x00, 0x06,
                0x01, 0x02, 0x03, 0x04,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x04,
                0x00, 0x00, 0x00, 0x05
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtcp());
        assertFalse(result.rtp());
        assertNotNull(result.rtcpHeader());
        assertEquals(2, result.rtcpHeader().version());
        assertEquals(200, result.rtcpHeader().packetType());
        assertEquals(6, result.rtcpHeader().length());
        assertEquals(28, result.rtcpHeader().packetLength());
        assertEquals(0x01020304L, result.rtcpHeader().senderSsrc());
        assertTrue(result.rtcpPacket() instanceof RtcpSenderReportPacket);
        RtcpSenderReportPacket senderReport = (RtcpSenderReportPacket) result.rtcpPacket();
        assertEquals(0x01020304L, senderReport.senderSsrc());
        assertEquals(0x00000003L, senderReport.rtpTimestamp());
        assertEquals(0x00000004L, senderReport.senderPacketCount());
        assertEquals(0x00000005L, senderReport.senderOctetCount());
    }

    @Test
    void shouldParseRtcpReceiverReportWithReceptionBlock() {
        byte[] packet = new byte[]{
                (byte) 0x81,
                (byte) 201,
                0x00, 0x07,
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66, 0x77, (byte) 0x88,
                0x05, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x64,
                0x00, 0x00, 0x00, 0x10,
                0x00, 0x00, 0x12, 0x34,
                0x00, 0x00, 0x56, 0x78
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtcp());
        assertTrue(result.rtcpPacket() instanceof RtcpReceiverReportPacket);
        RtcpReceiverReportPacket receiverReport = (RtcpReceiverReportPacket) result.rtcpPacket();
        assertEquals(0x11223344L, receiverReport.senderSsrc());
        assertEquals(1, receiverReport.reportBlocks().size());
        RtcpReportBlock block = receiverReport.reportBlocks().get(0);
        assertEquals(0x55667788L, block.ssrc());
        assertEquals(5, block.fractionLost());
        assertEquals(3, block.cumulativeLost());
        assertEquals(100L, block.extendedHighestSequenceNumber());
        assertEquals(16L, block.interarrivalJitter());
        assertEquals(0x1234L, block.lastSenderReport());
        assertEquals(0x5678L, block.delaySinceLastSenderReport());
    }

    @Test
    void shouldReturnNullForInvalidPacket() {
        assertNull(parser.parse(null));
        assertNull(parser.parse(new byte[]{0x00, 0x01, 0x02, 0x03}));
        assertNull(parser.parse(new byte[]{(byte) 0x80, 0x60, 0x00}));
    }

    @Test
    void shouldParseRtcpGenericNack() {
        byte[] packet = new byte[]{
                (byte) 0x81,
                (byte) 205,
                0x00, 0x03,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x12, 0x34,
                0x00, 0x05
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtcp());
        assertTrue(result.rtcpPacket() instanceof RtcpGenericNackPacket);
        RtcpGenericNackPacket nack = (RtcpGenericNackPacket) result.rtcpPacket();
        assertEquals(0x01020304L, nack.senderSsrc());
        assertEquals(0x11223344L, nack.mediaSsrc());
        assertEquals(Arrays.asList(Integer.valueOf(0x1234), Integer.valueOf(0x1235), Integer.valueOf(0x1237)), nack.lostSequenceNumbers());
    }

    @Test
    void shouldParseRtcpPictureLossIndication() {
        byte[] packet = new byte[]{
                (byte) 0x81,
                (byte) 206,
                0x00, 0x02,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44
        };

        RtpParseResult result = parser.parse(packet);

        assertNotNull(result);
        assertTrue(result.rtcp());
        assertTrue(result.rtcpPacket() instanceof RtcpPictureLossIndicationPacket);
        RtcpPictureLossIndicationPacket pli = (RtcpPictureLossIndicationPacket) result.rtcpPacket();
        assertEquals(0x01020304L, pli.senderSsrc());
        assertEquals(0x11223344L, pli.mediaSsrc());
    }
}
