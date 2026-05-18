package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;

import java.util.Arrays;

public final class CanonicalAudioFrame {

    private final InboundMediaFrame sourceFrame;
    private final CodecType codecType;
    private final byte[] payload;
    private final boolean configFrame;

    public CanonicalAudioFrame(
            InboundMediaFrame sourceFrame,
            CodecType codecType,
            byte[] payload,
            boolean configFrame
    ) {
        this.sourceFrame = sourceFrame;
        this.codecType = codecType == null ? CodecType.UNKNOWN : codecType;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        this.configFrame = configFrame;
    }

    public InboundMediaFrame sourceFrame() {
        return sourceFrame;
    }

    public CodecType codecType() {
        return codecType;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public boolean configFrame() {
        return configFrame;
    }
}
