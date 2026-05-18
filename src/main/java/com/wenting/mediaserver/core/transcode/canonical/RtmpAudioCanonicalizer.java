package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public final class RtmpAudioCanonicalizer implements AudioFrameCanonicalizer {

    @Override
    public CanonicalAudioFrame canonicalize(InboundMediaFrame frame) {
        if (frame == null || frame.trackType() != TrackType.AUDIO) {
            return null;
        }
        if (frame.codecType() != CodecType.AAC
                && frame.codecType() != CodecType.MPEG4_GENERIC
                && frame.codecType() != CodecType.G711A
                && frame.codecType() != CodecType.G711U) {
            return null;
        }
        return new CanonicalAudioFrame(frame, frame.codecType(), frame.payload(), frame.configFrame());
    }

    @Override
    public void close() {
    }
}
