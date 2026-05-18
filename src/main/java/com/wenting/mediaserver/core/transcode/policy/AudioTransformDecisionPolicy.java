package com.wenting.mediaserver.core.transcode.policy;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;

public interface AudioTransformDecisionPolicy {

    TransformDecision decide(StreamKey sourceKey, CanonicalAudioFrame frame, TransformDecision currentDecision);
}
