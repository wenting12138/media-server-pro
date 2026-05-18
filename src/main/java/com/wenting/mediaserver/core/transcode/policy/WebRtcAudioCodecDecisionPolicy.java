package com.wenting.mediaserver.core.transcode.policy;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;

public final class WebRtcAudioCodecDecisionPolicy implements AudioTransformDecisionPolicy {

    @Override
    public TransformDecision decide(StreamKey sourceKey, CanonicalAudioFrame frame, TransformDecision currentDecision) {
        if (currentDecision != null && currentDecision != TransformDecision.PENDING) {
            return currentDecision;
        }
        if (frame == null) {
            return TransformDecision.PENDING;
        }
        CodecType codecType = frame.codecType();
        if (codecType == CodecType.G711U) {
            return TransformDecision.PASSTHROUGH;
        }
        if (codecType == CodecType.AAC
                || codecType == CodecType.MPEG4_GENERIC
                || codecType == CodecType.G711A) {
            return TransformDecision.TRANSCODE;
        }
        return TransformDecision.PENDING;
    }
}
