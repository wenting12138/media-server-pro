package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.report.RtpReorderBuffer;
import com.wenting.mediaserver.core.publish.report.RtpReorderResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpReorderBufferTest {

    @Test
    void shouldReleaseBufferedPacketsInSequenceOrder() {
        RtpReorderBuffer buffer = new RtpReorderBuffer(8, 16);

        RtpReorderResult first = buffer.offer(packet(100), header(100));
        assertEquals(1, first.orderedPackets().size());
        assertEquals(100, first.orderedPackets().get(0).frame().payload()[0] & 0xFF);

        RtpReorderResult second = buffer.offer(packet(102), header(102));
        assertTrue(second.orderedPackets().isEmpty());

        RtpReorderResult third = buffer.offer(packet(101), header(101));
        assertEquals(2, third.orderedPackets().size());
        assertEquals(101, third.orderedPackets().get(0).frame().payload()[0] & 0xFF);
        assertEquals(102, third.orderedPackets().get(1).frame().payload()[0] & 0xFF);
        assertEquals(1, third.reorderedReleasedPackets());
    }

    @Test
    void shouldSkipLargeGapAndResumeFromNewSequence() {
        RtpReorderBuffer buffer = new RtpReorderBuffer(4, 8);

        buffer.offer(packet(100), header(100));
        RtpReorderResult result = buffer.offer(packet(110), header(110));

        assertEquals(1, result.orderedPackets().size());
        assertEquals(110, result.orderedPackets().get(0).frame().payload()[0] & 0xFF);
        assertEquals(9, result.gapSkippedPackets());
    }

    @Test
    void shouldHoldOutOfOrderPacketsUntilMissingSequenceArrives() {
        RtpReorderBuffer buffer = new RtpReorderBuffer(8, 16);

        RtpReorderResult first = buffer.offer(packet(100), header(100));
        assertEquals(1, first.orderedPackets().size());

        RtpReorderResult outOfOrder = buffer.offer(packet(102), header(102));
        assertTrue(outOfOrder.orderedPackets().isEmpty());

        RtpReorderResult recovered = buffer.offer(packet(101), header(101));
        assertEquals(2, recovered.orderedPackets().size());
        assertEquals(101, recovered.orderedPackets().get(0).frame().payload()[0] & 0xFF);
        assertEquals(102, recovered.orderedPackets().get(1).frame().payload()[0] & 0xFF);
        assertEquals(1, recovered.reorderedReleasedPackets());
    }

    private static InboundRtpPacket packet(int sequenceNumber) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[]{(byte) sequenceNumber}
                ),
                90000,
                false,
                MediaPacketTransport.UDP,
                Integer.valueOf(20000),
                null
        );
    }

    private static RtpPacketHeader header(int sequenceNumber) {
        return new RtpPacketHeader(2, false, false, 0, false, 96, sequenceNumber, 0L, 1L, 12, 12, 0);
    }
}
