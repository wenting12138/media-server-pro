package com.wenting.mediaserver.core.transcode.policy;

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

final class WebRtcH264ProfileDecisionPolicyTest {

    @Test
    void choosesPassthroughForBrowserSafeProfiles() {
        WebRtcH264ProfileDecisionPolicy policy = new WebRtcH264ProfileDecisionPolicy();
        TransformDecision decision = policy.decide(
                new StreamKey(StreamProtocol.RTMP, "live", "safe"),
                frame("42001f"),
                TransformDecision.PENDING
        );
        Assertions.assertEquals(TransformDecision.PASSTHROUGH, decision);
    }

    @Test
    void choosesTranscodeForHigh422Profiles() {
        WebRtcH264ProfileDecisionPolicy policy = new WebRtcH264ProfileDecisionPolicy();
        TransformDecision decision = policy.decide(
                new StreamKey(StreamProtocol.RTMP, "live", "422"),
                frame("7a001f"),
                TransformDecision.PENDING
        );
        Assertions.assertEquals(TransformDecision.TRANSCODE, decision);
    }

    private CanonicalVideoFrame frame(String profileLevelId) {
        return new CanonicalVideoFrame(
                new InboundMediaFrame(
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
                        new byte[0]
                ),
                VideoPayloadFormat.H264_AVCC,
                new byte[0],
                true,
                true,
                new H264CodecConfig(4, new byte[]{0x67, 0x42, 0x00, 0x1f}, new byte[]{0x68, 0x00}, profileLevelId)
        );
    }
}
