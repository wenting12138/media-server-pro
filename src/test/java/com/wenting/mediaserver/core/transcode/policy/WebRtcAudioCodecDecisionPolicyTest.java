package com.wenting.mediaserver.core.transcode.policy;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WebRtcAudioCodecDecisionPolicyTest {

    private final WebRtcAudioCodecDecisionPolicy policy = new WebRtcAudioCodecDecisionPolicy();

    @Test
    void shouldPassthroughG711U() {
        assertEquals(
                TransformDecision.PASSTHROUGH,
                policy.decide(streamKey(), frame(CodecType.G711U, false), TransformDecision.PENDING)
        );
    }

    @Test
    void shouldTranscodeAac() {
        assertEquals(
                TransformDecision.TRANSCODE,
                policy.decide(streamKey(), frame(CodecType.AAC, false), TransformDecision.PENDING)
        );
    }

    @Test
    void shouldTranscodeG711A() {
        assertEquals(
                TransformDecision.TRANSCODE,
                policy.decide(streamKey(), frame(CodecType.G711A, false), TransformDecision.PENDING)
        );
    }

    private CanonicalAudioFrame frame(CodecType codecType, boolean configFrame) {
        InboundMediaFrame sourceFrame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                codecType,
                "publisher",
                streamKey(),
                "audio",
                Long.valueOf(0L),
                Long.valueOf(0L),
                false,
                configFrame,
                null,
                new byte[]{0x01, 0x02}
        );
        return new CanonicalAudioFrame(sourceFrame, codecType, sourceFrame.payload(), configFrame);
    }

    private StreamKey streamKey() {
        return new StreamKey(StreamProtocol.RTMP, "live", "audio01");
    }
}
