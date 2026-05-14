package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.transcode.orchestrator.StreamTransformOrchestrator;
import com.wenting.mediaserver.core.transcode.orchestrator.WebRtcPlaybackStreamTransformOrchestrator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class StreamRegistryWebRtcPlaybackTest {

    @Test
    void registerPublishedStreamPrecreatesDerivedPlaybackStream() {
        StreamRegistry registry = new StreamRegistry();
        StreamTransformOrchestrator orchestrator = new WebRtcPlaybackStreamTransformOrchestrator(
                registry,
                registry.webRtcPlaybackSuffix()
        );
        registry.setStreamTransformOrchestrator(orchestrator);
        try {
            StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
            IPublishedStream sourceStream = new DefaultPublishedStream(sourceKey);

            registry.registerPublishedStream(sourceKey, sourceStream);

            IPublishedStream derived = registry.findPublishedStreamByPath("live", "camera__webrtc");
            Assertions.assertNotNull(derived);
            Assertions.assertSame(derived, registry.findPublishedStreamForWebRtcPlayback("live", "camera"));
        } finally {
            orchestrator.close();
        }
    }
}
