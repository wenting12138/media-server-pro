package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsMuxerTest {

    private static final StreamKey STREAM_KEY = new StreamKey(StreamProtocol.RTMP, "live", "mux");

    @Test
    void shouldWritePatAndPmtBeforeFirstH264PesPacket() {
        TsMuxer muxer = new TsMuxer();

        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 0L, 0L, true, true, h264Config()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 0L, 0L, false, true, aacConfig()));
        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 100L, 100L, true, false, h264KeyFramePayload()));

        byte[] bytes = muxer.bytes();
        assertEquals(0, bytes.length % 188);
        assertEquals(0x47, bytes[0] & 0xFF);
        assertEquals(0x47, bytes[188] & 0xFF);
        assertEquals(0x47, bytes[376] & 0xFF);
        assertEquals(0x0000, pidOf(bytes, 0));
        assertEquals(0x1000, pidOf(bytes, 188));
        assertEquals(0x0100, pidOf(bytes, 376));
    }

    @Test
    void shouldPrependSpsAndPpsBeforeH264KeyFramePayload() {
        TsMuxer muxer = new TsMuxer();

        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 0L, 0L, true, true, h264Config()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 0L, 0L, false, true, aacConfig()));
        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 100L, 100L, true, false, h264KeyFramePayload()));

        byte[] bytes = muxer.bytes();
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x1F}));
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80}));
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22}));
    }

    @Test
    void shouldUseHevcStreamTypeAndPrependVpsSpsPpsForH265KeyFrame() {
        TsMuxer muxer = new TsMuxer();

        muxer.onFrame(frame("video-h265", TrackType.VIDEO, CodecType.H265, 0L, 0L, true, true, h265Config()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 0L, 0L, false, true, aacConfig()));
        muxer.onFrame(frame("video-h265", TrackType.VIDEO, CodecType.H265, 100L, 100L, true, false, h265KeyFramePayload()));

        byte[] bytes = muxer.bytes();
        assertTrue(containsSequence(bytes, new byte[] {0x24, (byte) 0xE1, 0x00, (byte) 0xF0, 0x00, 0x0F}));
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0C}));
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x42, 0x01, 0x01}));
        assertTrue(containsSequence(bytes, new byte[] {0x00, 0x00, 0x00, 0x01, 0x44, 0x01, (byte) 0xC0}));
        assertTrue(containsSequence(bytes, new byte[] {0x26, 0x01, 0x55, 0x66}));
    }

    @Test
    void shouldWrapAacFrameWithAdtsAndWriteAudioPid() {
        TsMuxer muxer = new TsMuxer();

        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 0L, 0L, true, true, h264Config()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 0L, 0L, false, true, aacConfig()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 100L, 100L, false, false, new byte[] {0x01, 0x02, 0x03}));

        byte[] bytes = muxer.bytes();
        assertTrue(containsSequence(bytes, new byte[] {(byte) 0xFF, (byte) 0xF1}));
        assertTrue(containsPacketWithPid(bytes, 0x0101));
    }

    @Test
    void shouldWriteTablesAgainAfterReset() {
        TsMuxer muxer = new TsMuxer();

        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 0L, 0L, true, true, h264Config()));
        muxer.onFrame(frame("audio-aac", TrackType.AUDIO, CodecType.AAC, 0L, 0L, false, true, aacConfig()));
        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 100L, 100L, true, false, h264KeyFramePayload()));
        byte[] firstBytes = muxer.bytes();
        assertTrue(firstBytes.length >= 376);

        muxer.reset();
        muxer.onFrame(frame("video-h264", TrackType.VIDEO, CodecType.H264, 200L, 200L, true, false, h264KeyFramePayload()));

        byte[] secondBytes = muxer.bytes();
        assertEquals(0x0000, pidOf(secondBytes, 0));
        assertEquals(0x1000, pidOf(secondBytes, 188));
    }

    private static InboundMediaFrame frame(
            String trackId,
            TrackType trackType,
            CodecType codecType,
            Long ptsMillis,
            Long dtsMillis,
            boolean keyFrame,
            boolean configFrame,
            byte[] payload
    ) {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                trackType,
                codecType,
                "publisher",
                STREAM_KEY,
                trackId,
                ptsMillis,
                dtsMillis,
                keyFrame,
                configFrame,
                null,
                payload
        );
    }

    private static int pidOf(byte[] bytes, int offset) {
        return ((bytes[offset + 1] & 0x1F) << 8) | (bytes[offset + 2] & 0xFF);
    }

    private static boolean containsPacketWithPid(byte[] bytes, int pid) {
        for (int offset = 0; offset + 188 <= bytes.length; offset += 188) {
            if (pidOf(bytes, offset) == pid) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSequence(byte[] bytes, byte[] target) {
        if (target.length == 0 || bytes.length < target.length) {
            return false;
        }
        for (int i = 0; i <= bytes.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static byte[] h264Config() {
        return new byte[] {
                0x01, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE1,
                0x00, 0x08, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78,
                0x01, 0x00, 0x04, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
        };
    }

    private static byte[] h264KeyFramePayload() {
        return new byte[] {0x00, 0x00, 0x00, 0x03, 0x65, 0x11, 0x22};
    }

    private static byte[] h265Config() {
        return new byte[] {
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0, 0x00,
                (byte) 0xFC, (byte) 0xFD, 0x03,
                0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0C,
                0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x01,
                0x22, 0x00, 0x01, 0x00, 0x03, 0x44, 0x01, (byte) 0xC0
        };
    }

    private static byte[] h265KeyFramePayload() {
        return new byte[] {0x00, 0x00, 0x00, 0x04, 0x26, 0x01, 0x55, 0x66};
    }

    private static byte[] aacConfig() {
        return new byte[] {0x11, (byte) 0x90};
    }
}
