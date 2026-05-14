package com.wenting.mediaserver.core.transcode.policy;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;

public interface TranscodeDecisionPolicy {

    TransformDecision decide(StreamKey sourceKey, CanonicalVideoFrame frame, TransformDecision currentDecision);
}
