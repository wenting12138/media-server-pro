package com.wenting.mediaserver.core.transcode.engine;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;
import com.wenting.mediaserver.core.transcode.canonical.H264CodecConfig;
import com.wenting.mediaserver.core.transcode.canonical.VideoPayloadFormat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

final class H264Avcc420pTranscoderTest {

    @Test
    void detectsIdrFromAvccPayloadWithoutRtmpHeader() throws Exception {
        H264Avcc420pTranscoder transcoder = new H264Avcc420pTranscoder();
        try {
            CanonicalVideoFrame configFrame = new CanonicalVideoFrame(
                    frame(true, true, new byte[0]),
                    VideoPayloadFormat.H264_AVCC,
                    new byte[0],
                    true,
                    true,
                    new H264CodecConfig(4, new byte[]{0x67, 0x42, 0x00, 0x1f}, new byte[]{0x68, 0x00}, "42001f")
            );
            transcoder.transcode(configFrame, new StreamKey(StreamProtocol.RTMP, "live", "derived"));

            Method containsAvccIdr = H264Avcc420pTranscoder.class.getDeclaredMethod("containsAvccIdr", byte[].class);
            containsAvccIdr.setAccessible(true);
            boolean result = (Boolean) containsAvccIdr.invoke(
                    transcoder,
                    new byte[]{0x00, 0x00, 0x00, 0x04, 0x65, (byte) 0x88, (byte) 0x84, 0x21}
            );

            Assertions.assertTrue(result);
        } finally {
            transcoder.close();
        }
    }

    @Test
    void emitsPureInternalAvccPayloadWithoutRtmpVideoHeader() throws Exception {
        H264Avcc420pTranscoder transcoder = new H264Avcc420pTranscoder();
        try {
            Method annexbToAvccPayload = H264Avcc420pTranscoder.class.getDeclaredMethod("annexbToAvccPayload", byte[].class);
            annexbToAvccPayload.setAccessible(true);

            byte[] annexb = new byte[]{
                    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
                    0x00, 0x00, 0x00, 0x01, 0x68, 0x00,
                    0x00, 0x00, 0x00, 0x01, 0x65, (byte) 0x88, (byte) 0x84, 0x21
            };
            Object output = annexbToAvccPayload.invoke(transcoder, new Object[]{annexb});

            Assertions.assertNotNull(output);

            Field payloadBytes = output.getClass().getDeclaredField("payloadBytes");
            Field sequenceHeaderBytes = output.getClass().getDeclaredField("sequenceHeaderBytes");
            payloadBytes.setAccessible(true);
            sequenceHeaderBytes.setAccessible(true);

            byte[] mediaPayload = (byte[]) payloadBytes.get(output);
            byte[] configPayload = (byte[]) sequenceHeaderBytes.get(output);

            Assertions.assertTrue(mediaPayload.length > 8);
            Assertions.assertNotEquals(0x17, mediaPayload[0] & 0xFF);
            Assertions.assertEquals(0x00, mediaPayload[0] & 0xFF);
            Assertions.assertEquals(0x00, mediaPayload[1] & 0xFF);
            Assertions.assertEquals(0x00, mediaPayload[2] & 0xFF);
            Assertions.assertEquals(0x04, mediaPayload[3] & 0xFF);
            Assertions.assertEquals(0x67, mediaPayload[4] & 0xFF);
            Assertions.assertEquals(0x01, configPayload[0] & 0xFF);
            Assertions.assertNotEquals(0x17, configPayload[0] & 0xFF);
        } finally {
            transcoder.close();
        }
    }

    @Test
    void requestKeyFrameShouldArmSequenceHeaderReplay() throws Exception {
        H264Avcc420pTranscoder transcoder = new H264Avcc420pTranscoder();
        try {
            Field lastSequenceHeaderBytes = H264Avcc420pTranscoder.class.getDeclaredField("lastSequenceHeaderBytes");
            Field pendingSequenceHeaderBytes = H264Avcc420pTranscoder.class.getDeclaredField("pendingSequenceHeaderBytes");
            Field forceNextKeyFrameRequested = H264Avcc420pTranscoder.class.getDeclaredField("forceNextKeyFrameRequested");
            lastSequenceHeaderBytes.setAccessible(true);
            pendingSequenceHeaderBytes.setAccessible(true);
            forceNextKeyFrameRequested.setAccessible(true);

            byte[] sequenceHeader = new byte[]{0x01, 0x64, 0x00, 0x1F};
            lastSequenceHeaderBytes.set(transcoder, sequenceHeader);

            Assertions.assertTrue(transcoder.requestKeyFrame());
            Assertions.assertTrue((Boolean) forceNextKeyFrameRequested.get(transcoder));
            Assertions.assertTrue(Arrays.equals(sequenceHeader, (byte[]) pendingSequenceHeaderBytes.get(transcoder)));
        } finally {
            transcoder.close();
        }
    }

    private InboundMediaFrame frame(boolean keyFrame, boolean configFrame, byte[] payload) {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "session-1",
                new StreamKey(StreamProtocol.RTMP, "live", "camera"),
                "video-h264",
                0L,
                0L,
                keyFrame,
                configFrame,
                null,
                payload
        );
    }
}
