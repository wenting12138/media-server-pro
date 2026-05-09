package com.wenting.mediaserver.core.remux.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpToRtmpFrameAssemblerTest {

    @Test
    void shouldAssembleH264StapAWithSpsPpsAndIdrIntoConfigAndKeyFrame() {
        RtpToRtmpFrameAssembler assembler = new RtpToRtmpFrameAssembler();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam01");
        byte[] sps = new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78};
        byte[] pps = new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80};
        byte[] idr = new byte[]{0x65, 0x11, 0x22};
        byte[] stapA = new byte[1 + 2 + sps.length + 2 + pps.length + 2 + idr.length];
        int index = 0;
        stapA[index++] = 0x78;
        index = appendLengthPrefixedNal(stapA, index, sps);
        index = appendLengthPrefixedNal(stapA, index, pps);
        appendLengthPrefixedNal(stapA, index, idr);

        List<InboundMediaFrame> frames = assembler.assemble(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                true,
                stapA
        ), null);

        assertEquals(2, frames.size());
        assertTrue(frames.get(0).configFrame());
        assertTrue(frames.get(1).keyFrame());
        assertNotNull(frames.get(0).payload());
        assertNotNull(frames.get(1).payload());
        assertEquals(0x01, frames.get(0).payload()[0] & 0xFF);
        assertEquals(0x00, frames.get(1).payload()[0] & 0xFF);
        assertEquals(0x00, frames.get(1).payload()[1] & 0xFF);
        assertEquals(0x00, frames.get(1).payload()[2] & 0xFF);
        assertEquals(idr.length, frames.get(1).payload()[3] & 0xFF);
        assertEquals(0x65, frames.get(1).payload()[4] & 0xFF);
    }

    @Test
    void shouldAssembleG711RtpIntoSingleRtmpAudioFrame() {
        RtpToRtmpFrameAssembler assembler = new RtpToRtmpFrameAssembler();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam02");

        List<InboundMediaFrame> frames = assembler.assemble(rtpPacket(
                streamKey,
                "audio-g711a",
                CodecType.G711A,
                TrackType.AUDIO,
                1,
                160L,
                true,
                new byte[]{0x11, 0x22, 0x33, 0x44}
        ), null);

        assertEquals(1, frames.size());
        assertEquals(CodecType.G711A, frames.get(0).codecType());
        assertEquals(TrackType.AUDIO, frames.get(0).trackType());
        assertEquals(4, frames.get(0).payloadLength());
        assertEquals(0x11, frames.get(0).payload()[0] & 0xFF);
        assertEquals(0x44, frames.get(0).payload()[3] & 0xFF);
    }

    @Test
    void shouldAssembleG711uRtpIntoSingleRtmpAudioFrame() {
        RtpToRtmpFrameAssembler assembler = new RtpToRtmpFrameAssembler();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam03");

        List<InboundMediaFrame> frames = assembler.assemble(rtpPacket(
                streamKey,
                "audio-g711u",
                CodecType.G711U,
                TrackType.AUDIO,
                1,
                160L,
                true,
                new byte[]{0x21, 0x32, 0x43}
        ), null);

        assertEquals(1, frames.size());
        assertEquals(CodecType.G711U, frames.get(0).codecType());
        assertEquals(TrackType.AUDIO, frames.get(0).trackType());
        assertEquals(3, frames.get(0).payloadLength());
        assertEquals(0x21, frames.get(0).payload()[0] & 0xFF);
        assertEquals(0x43, frames.get(0).payload()[2] & 0xFF);
    }

    private static int appendLengthPrefixedNal(byte[] target, int index, byte[] nal) {
        target[index++] = (byte) ((nal.length >> 8) & 0xFF);
        target[index++] = (byte) (nal.length & 0xFF);
        System.arraycopy(nal, 0, target, index, nal.length);
        return index + nal.length;
    }

    private static InboundRtpPacket rtpPacket(
            StreamKey streamKey,
            String trackId,
            CodecType codecType,
            TrackType trackType,
            int sequenceNumber,
            long timestamp,
            boolean marker,
            byte[] rtpPayload
    ) {
        byte[] packet = new byte[12 + rtpPayload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (marker ? 0xE0 : 0x60);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = 0x11;
        packet[9] = 0x22;
        packet[10] = 0x33;
        packet[11] = 0x44;
        System.arraycopy(rtpPayload, 0, packet, 12, rtpPayload.length);
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        trackType,
                        codecType,
                        "publisher",
                        streamKey,
                        trackId,
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                trackType == TrackType.AUDIO ? 8000 : 90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );
    }
}
