package com.wenting.mediaserver.core.transcode.policy;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;
import com.wenting.mediaserver.core.transcode.canonical.H264CodecConfig;

public final class WebRtcH264ProfileDecisionPolicy implements TranscodeDecisionPolicy {

    @Override
    public TransformDecision decide(
            StreamKey sourceKey,
            CanonicalVideoFrame frame,
            TransformDecision currentDecision
    ) {
        if (currentDecision != null && currentDecision != TransformDecision.PENDING) {
            return currentDecision;
        }
        if (frame == null) {
            return TransformDecision.PENDING;
        }
        H264CodecConfig config = frame.h264CodecConfig();
        if (config == null || !config.complete()) {
            return TransformDecision.PENDING;
        }
        int profileIdc = parseProfileIdc(config.profileLevelId());
        if (profileIdc == 0x42) {
            return TransformDecision.PASSTHROUGH;
        }
        return TransformDecision.TRANSCODE;
    }

    private int parseProfileIdc(String profileLevelId) {
        if (profileLevelId == null || profileLevelId.length() < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(profileLevelId.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
