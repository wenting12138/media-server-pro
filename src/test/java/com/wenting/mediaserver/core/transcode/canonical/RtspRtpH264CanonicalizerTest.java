package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

final class RtspRtpH264CanonicalizerTest {

    @Test
    void reassemblesFuAIntoConfigAndKeyframe() {
        RtspRtpH264Canonicalizer canonicalizer = new RtspRtpH264Canonicalizer();
        ITrack track = new ITrack() {
            @Override
            public String trackId() {
                return "video";
            }

            @Override
            public CodecType codecType() {
                return CodecType.H264;
            }

            @Override
            public TrackType trackType() {
                return TrackType.VIDEO;
            }

            @Override
            public String connectionAddress() {
                return null;
            }

            @Override
            public int clockRate() {
                return 90000;
            }

            @Override
            public byte[] h264Sps() {
                return new byte[]{0x67, 0x42, 0x00, 0x1f};
            }

            @Override
            public byte[] h264Pps() {
                return new byte[]{0x68, (byte) 0xce, 0x06, (byte) 0xe2};
            }
        };
        try {
            InboundRtpPacket startPacket = packet(new byte[]{0x7c, (byte) 0x85, (byte) 0x88, (byte) 0x84}, false, 1234);
            InboundRtpPacket endPacket = packet(new byte[]{0x7c, 0x45, 0x21}, true, 1234);

            Assertions.assertTrue(canonicalizer.canonicalize(startPacket, track).isEmpty());

            List<CanonicalVideoFrame> frames = canonicalizer.canonicalize(endPacket, track);
            Assertions.assertEquals(2, frames.size());

            CanonicalVideoFrame config = frames.get(0);
            Assertions.assertTrue(config.configFrame());
            Assertions.assertEquals("42001f", config.h264CodecConfig().profileLevelId());

            CanonicalVideoFrame media = frames.get(1);
            Assertions.assertFalse(media.configFrame());
            Assertions.assertTrue(media.keyFrame());
            Assertions.assertArrayEquals(
                    new byte[]{0x00, 0x00, 0x00, 0x04, 0x65, (byte) 0x88, (byte) 0x84, 0x21},
                    media.payload()
            );
        } finally {
            canonicalizer.close();
        }
    }

    private InboundRtpPacket packet(byte[] rtpPayload, boolean marker, long timestamp) {
        byte[] packet = new byte[12 + rtpPayload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | 96);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        System.arraycopy(rtpPayload, 0, packet, 12, rtpPayload.length);
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "rtsp-session",
                        new StreamKey(StreamProtocol.RTSP, "live", "camera"),
                        "video",
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );
    }
}
