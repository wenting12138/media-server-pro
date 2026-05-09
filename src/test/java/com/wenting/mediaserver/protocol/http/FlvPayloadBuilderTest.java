package com.wenting.mediaserver.protocol.http.flv;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlvPayloadBuilderTest {

    private final FlvPayloadBuilder builder = new FlvPayloadBuilder();

    @Test
    void shouldBuildH264VideoPayload() {
        InboundMediaFrame frame = frame(
                TrackType.VIDEO,
                CodecType.H264,
                Long.valueOf(120L),
                Long.valueOf(100L),
                true,
                false,
                new byte[] {0x01, 0x02, 0x03}
        );

        byte[] payload = builder.toVideoPayload(frame);

        assertEquals(8, payload.length);
        assertEquals(0x17, payload[0] & 0xFF);
        assertEquals(0x01, payload[1] & 0xFF);
        assertEquals(0x00, payload[2] & 0xFF);
        assertEquals(0x00, payload[3] & 0xFF);
        assertEquals(0x14, payload[4] & 0xFF);
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, new byte[] {payload[5], payload[6], payload[7]});
    }

    @Test
    void shouldBuildH265VideoConfigPayload() {
        InboundMediaFrame frame = frame(
                TrackType.VIDEO,
                CodecType.H265,
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                new byte[] {0x11, 0x22}
        );

        byte[] payload = builder.toVideoPayload(frame);

        assertEquals(0x1C, payload[0] & 0xFF);
        assertEquals(0x00, payload[1] & 0xFF);
        assertEquals(0x00, payload[2] & 0xFF);
        assertEquals(0x00, payload[3] & 0xFF);
        assertEquals(0x00, payload[4] & 0xFF);
        assertEquals(0x11, payload[5] & 0xFF);
        assertEquals(0x22, payload[6] & 0xFF);
    }

    @Test
    void shouldBuildAacAudioPayload() {
        InboundMediaFrame frame = frame(
                TrackType.AUDIO,
                CodecType.AAC,
                Long.valueOf(20L),
                Long.valueOf(20L),
                false,
                false,
                new byte[] {0x55, 0x66}
        );

        byte[] payload = builder.toAudioPayload(frame);

        assertEquals(4, payload.length);
        assertEquals(0xAF, payload[0] & 0xFF);
        assertEquals(0x01, payload[1] & 0xFF);
        assertEquals(0x55, payload[2] & 0xFF);
        assertEquals(0x66, payload[3] & 0xFF);
    }

    @Test
    void shouldBuildG711uAudioPayload() {
        InboundMediaFrame frame = frame(
                TrackType.AUDIO,
                CodecType.G711U,
                Long.valueOf(0L),
                Long.valueOf(0L),
                false,
                false,
                new byte[] {0x21, 0x22}
        );

        byte[] payload = builder.toAudioPayload(frame);

        assertEquals(3, payload.length);
        assertEquals(0x82, payload[0] & 0xFF);
        assertEquals(0x21, payload[1] & 0xFF);
        assertEquals(0x22, payload[2] & 0xFF);
    }

    @Test
    void shouldResolveTimestampFromDtsFirst() {
        InboundMediaFrame frame = frame(
                TrackType.VIDEO,
                CodecType.H264,
                Long.valueOf(60L),
                Long.valueOf(40L),
                false,
                false,
                new byte[] {0x01}
        );

        assertEquals(40L, builder.resolveTimestamp(frame));
    }

    private static InboundMediaFrame frame(
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
                new StreamKey(StreamProtocol.RTMP, "live", "test"),
                "track",
                ptsMillis,
                dtsMillis,
                keyFrame,
                configFrame,
                null,
                payload
        );
    }
}
