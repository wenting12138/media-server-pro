package com.wenting.mediaserver.core.remux.rtp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspFrameToRtpPacketizerTest {

    @Test
    void shouldFragmentLargeH264AccessUnitIntoFuAChunks() {
        RtspFrameToRtpPacketizer packetizer = new RtspFrameToRtpPacketizer();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        InboundMediaFrame configFrame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                streamKey,
                "video-h264",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{
                        0x01, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE1,
                        0x00, 0x08, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78,
                        0x01, 0x00, 0x04, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
                }
        );
        byte[] nal = new byte[2000];
        nal[0] = 0x65;
        for (int i = 1; i < nal.length; i++) {
            nal[i] = (byte) (i & 0xFF);
        }
        byte[] payload = new byte[4 + nal.length];
        payload[0] = 0x00;
        payload[1] = 0x00;
        payload[2] = 0x07;
        payload[3] = (byte) 0xD0;
        System.arraycopy(nal, 0, payload, 4, nal.length);
        InboundMediaFrame frame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                streamKey,
                "video-h264",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                payload
        );

        List<RtpPacketChunk> chunks = packetizer.packetize(frame, null, configFrame);

        assertTrue(chunks.size() > 1);
        assertEquals(28, chunks.get(0).payload()[0] & 0x1F);
        assertTrue((chunks.get(0).payload()[1] & 0x80) != 0);
        assertFalse(chunks.get(0).marker());
        RtpPacketChunk last = chunks.get(chunks.size() - 1);
        assertEquals(28, last.payload()[0] & 0x1F);
        assertTrue((last.payload()[1] & 0x40) != 0);
        assertTrue(last.marker());
    }

    @Test
    void shouldFragmentLargeH265AccessUnitIntoFuChunks() {
        RtspFrameToRtpPacketizer packetizer = new RtspFrameToRtpPacketizer();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        InboundMediaFrame configFrame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H265,
                "publisher",
                streamKey,
                "video-h265",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{
                        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x03,
                        0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0C,
                        0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x01,
                        0x22, 0x00, 0x01, 0x00, 0x03, 0x44, 0x01, (byte) 0xC0
                }
        );
        byte[] nal = new byte[2000];
        nal[0] = 0x26;
        nal[1] = 0x01;
        for (int i = 2; i < nal.length; i++) {
            nal[i] = (byte) (i & 0xFF);
        }
        byte[] payload = new byte[4 + nal.length];
        payload[0] = 0x00;
        payload[1] = 0x00;
        payload[2] = 0x07;
        payload[3] = (byte) 0xD0;
        System.arraycopy(nal, 0, payload, 4, nal.length);
        InboundMediaFrame frame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H265,
                "publisher",
                streamKey,
                "video-h265",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                payload
        );

        List<RtpPacketChunk> chunks = packetizer.packetize(frame, null, configFrame);

        assertTrue(chunks.size() > 1);
        assertEquals(49, (chunks.get(0).payload()[0] & 0x7E) >> 1);
        assertTrue((chunks.get(0).payload()[2] & 0x80) != 0);
        assertFalse(chunks.get(0).marker());
        RtpPacketChunk last = chunks.get(chunks.size() - 1);
        assertEquals(49, (last.payload()[0] & 0x7E) >> 1);
        assertTrue((last.payload()[2] & 0x40) != 0);
        assertTrue(last.marker());
    }
}
