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

    @Test
    void derivedPlaybackStreamShouldForwardKeyframeRequestToSourceOrchestrator() {
        StreamRegistry registry = new StreamRegistry();
        final StreamKey[] requestedSourceKey = new StreamKey[1];
        final String[] requestedTrackId = new String[1];
        registry.setStreamTransformOrchestrator(new StreamTransformOrchestrator() {
            @Override
            public void onStreamRegistered(StreamKey sourceKey) {
            }

            @Override
            public void onStreamRemoved(StreamKey sourceKey) {
            }

            @Override
            public void onFrame(com.wenting.mediaserver.core.publish.InboundMediaFrame frame) {
            }

            @Override
            public boolean requestKeyFrame(StreamKey sourceKey, String trackId) {
                requestedSourceKey[0] = sourceKey;
                requestedTrackId[0] = trackId;
                return true;
            }

            @Override
            public void close() {
            }
        });

        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        registry.registerPublishedStream(sourceKey, new DefaultPublishedStream(sourceKey));
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        registry.registerPublishedStream(derivedKey, new DefaultPublishedStream(derivedKey));

        IPublishedStream derived = registry.findPublishedStreamByPath("live", "camera__webrtc");
        Assertions.assertNotNull(derived);
        Assertions.assertTrue(derived.requestKeyFrame("video-main"));
        Assertions.assertEquals(sourceKey, requestedSourceKey[0]);
        Assertions.assertEquals("video-main", requestedTrackId[0]);
    }
}
