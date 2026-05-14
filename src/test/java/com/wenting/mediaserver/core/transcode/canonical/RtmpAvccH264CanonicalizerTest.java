package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class RtmpAvccH264CanonicalizerTest {

    @Test
    void carriesParsedAvccConfigIntoFollowingMediaFrames() {
        RtmpAvccH264Canonicalizer canonicalizer = new RtmpAvccH264Canonicalizer();
        try {
            InboundMediaFrame configFrame = new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.VIDEO,
                    CodecType.H264,
                    "session-1",
                    new StreamKey(StreamProtocol.RTMP, "live", "camera"),
                    "video-h264",
                    0L,
                    0L,
                    true,
                    true,
                    null,
                    new byte[]{
                            0x01, 0x42, 0x00, 0x1f, (byte) 0xff,
                            (byte) 0xe1, 0x00, 0x04, 0x67, 0x42, 0x00, 0x1f,
                            0x01, 0x00, 0x04, 0x68, (byte) 0xce, 0x06, (byte) 0xe2
                    }
            );
            CanonicalVideoFrame canonicalConfig = canonicalizer.canonicalize(configFrame);

            Assertions.assertNotNull(canonicalConfig);
            Assertions.assertTrue(canonicalConfig.configFrame());
            Assertions.assertEquals(VideoPayloadFormat.H264_AVCC, canonicalConfig.payloadFormat());
            Assertions.assertNotNull(canonicalConfig.h264CodecConfig());
            Assertions.assertEquals(4, canonicalConfig.h264CodecConfig().nalLengthSize());
            Assertions.assertEquals("42001f", canonicalConfig.h264CodecConfig().profileLevelId());
            Assertions.assertArrayEquals(new byte[]{0x67, 0x42, 0x00, 0x1f}, canonicalConfig.h264CodecConfig().sps());
            Assertions.assertArrayEquals(new byte[]{0x68, (byte) 0xce, 0x06, (byte) 0xe2}, canonicalConfig.h264CodecConfig().pps());

            InboundMediaFrame mediaFrame = new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.VIDEO,
                    CodecType.H264,
                    "session-1",
                    new StreamKey(StreamProtocol.RTMP, "live", "camera"),
                    "video-h264",
                    40L,
                    40L,
                    true,
                    false,
                    null,
                    new byte[]{0x00, 0x00, 0x00, 0x04, 0x65, (byte) 0x88, (byte) 0x84, 0x21}
            );
            CanonicalVideoFrame canonicalMedia = canonicalizer.canonicalize(mediaFrame);

            Assertions.assertNotNull(canonicalMedia);
            Assertions.assertFalse(canonicalMedia.configFrame());
            Assertions.assertNotNull(canonicalMedia.h264CodecConfig());
            Assertions.assertEquals("42001f", canonicalMedia.h264CodecConfig().profileLevelId());
            Assertions.assertEquals(4, canonicalMedia.h264CodecConfig().nalLengthSize());
        } finally {
            canonicalizer.close();
        }
    }
}
