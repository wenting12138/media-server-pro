package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtmpFrameMapperTest {

    @Test
    void shouldMapH264VideoMessageToInboundFrame() {
        RtmpSession session = new RtmpSession("rtmp-session");
        session.streamKey(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        RtmpVideoFrameMapper mapper = new RtmpVideoFrameMapper();

        InboundMediaFrame frame = mapper.map(
                session,
                new RtmpVideoMessage(6, 100L, 1, new byte[]{0x17, 0x01, 0x00, 0x00, 0x05, 0x11, 0x22}),
                null
        );

        assertEquals(CodecType.H264, frame.codecType());
        assertTrue(frame.keyFrame());
        assertFalse(frame.configFrame());
        assertEquals(Long.valueOf(100L), frame.dtsMillis());
        assertEquals(Long.valueOf(105L), frame.ptsMillis());
        assertArrayEquals(new byte[]{0x11, 0x22}, frame.payload());
    }

    @Test
    void shouldMapEnhancedAvcSequenceStartToInboundFrame() {
        RtmpSession session = new RtmpSession("rtmp-session");
        session.streamKey(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        RtmpVideoFrameMapper mapper = new RtmpVideoFrameMapper();

        InboundMediaFrame frame = mapper.map(
                session,
                new RtmpVideoMessage(
                        6,
                        100L,
                        1,
                        new byte[]{(byte) 0x90, 0x61, 0x76, 0x63, 0x31, 0x01, 0x64, 0x00, 0x1F}
                ),
                null
        );

        assertEquals(CodecType.H264, frame.codecType());
        assertTrue(frame.keyFrame());
        assertTrue(frame.configFrame());
        assertEquals(Long.valueOf(100L), frame.dtsMillis());
        assertEquals(Long.valueOf(100L), frame.ptsMillis());
        assertArrayEquals(new byte[]{0x01, 0x64, 0x00, 0x1F}, frame.payload());
    }

    @Test
    void shouldMapEnhancedAvcCodedFramesToInboundFrame() {
        RtmpSession session = new RtmpSession("rtmp-session");
        session.streamKey(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        RtmpVideoFrameMapper mapper = new RtmpVideoFrameMapper();

        InboundMediaFrame frame = mapper.map(
                session,
                new RtmpVideoMessage(
                        6,
                        100L,
                        1,
                        new byte[]{(byte) 0x91, 0x61, 0x76, 0x63, 0x31, 0x00, 0x00, 0x05, 0x11, 0x22}
                ),
                null
        );

        assertEquals(CodecType.H264, frame.codecType());
        assertTrue(frame.keyFrame());
        assertFalse(frame.configFrame());
        assertEquals(Long.valueOf(100L), frame.dtsMillis());
        assertEquals(Long.valueOf(105L), frame.ptsMillis());
        assertArrayEquals(new byte[]{0x11, 0x22}, frame.payload());
    }

    @Test
    void shouldMapAacConfigMessageToInboundFrame() {
        RtmpSession session = new RtmpSession("rtmp-session");
        session.streamKey(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        RtmpAudioFrameMapper mapper = new RtmpAudioFrameMapper();

        InboundMediaFrame frame = mapper.map(
                session,
                new RtmpAudioMessage(4, 25L, 1, new byte[]{(byte) 0xAF, 0x00, 0x12, 0x10}),
                null
        );

        assertEquals(CodecType.AAC, frame.codecType());
        assertTrue(frame.configFrame());
        assertArrayEquals(new byte[]{0x12, 0x10}, frame.payload());
    }
}
