package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

import java.util.Arrays;

public final class CanonicalVideoFrame {

    private final InboundMediaFrame sourceFrame;
    private final VideoPayloadFormat payloadFormat;
    private final byte[] payload;
    private final boolean keyFrame;
    private final boolean configFrame;
    private final H264CodecConfig h264CodecConfig;

    public CanonicalVideoFrame(
            InboundMediaFrame sourceFrame,
            VideoPayloadFormat payloadFormat,
            byte[] payload,
            boolean keyFrame,
            boolean configFrame,
            H264CodecConfig h264CodecConfig
    ) {
        this.sourceFrame = sourceFrame;
        this.payloadFormat = payloadFormat == null ? VideoPayloadFormat.UNKNOWN : payloadFormat;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        this.keyFrame = keyFrame;
        this.configFrame = configFrame;
        this.h264CodecConfig = h264CodecConfig;
    }

    public InboundMediaFrame sourceFrame() {
        return sourceFrame;
    }

    public VideoPayloadFormat payloadFormat() {
        return payloadFormat;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public boolean keyFrame() {
        return keyFrame;
    }

    public boolean configFrame() {
        return configFrame;
    }

    public H264CodecConfig h264CodecConfig() {
        return h264CodecConfig;
    }
}
