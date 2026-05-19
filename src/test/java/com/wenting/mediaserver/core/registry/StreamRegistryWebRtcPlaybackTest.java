package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
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
    void legacyPlaybackShouldPreferDerivedStreamForWebRtcSourceAndHlsAudioSidecar() {
        StreamRegistry registry = new StreamRegistry();
        StreamTransformOrchestrator orchestrator = new WebRtcPlaybackStreamTransformOrchestrator(
                registry,
                registry.webRtcPlaybackSuffix()
        );
        registry.setStreamTransformOrchestrator(orchestrator);
        try {
            StreamKey sourceKey = new StreamKey(StreamProtocol.WEBRTC, "live", "browser01");
            IPublishedStream sourceStream = new DefaultPublishedStream(sourceKey);

            registry.registerPublishedStream(sourceKey, sourceStream);

            IPublishedStream derived = registry.findPublishedStreamByPath("live", "browser01__webrtc");
            Assertions.assertNotNull(derived);
            Assertions.assertSame(derived, registry.findPublishedStreamForRtspPlayback("live", "browser01"));
            Assertions.assertSame(derived, registry.findPublishedStreamForRtmpPlayback("live", "browser01"));
            Assertions.assertSame(derived, registry.findPublishedStreamForHttpFlvPlayback("live", "browser01"));
            Assertions.assertSame(sourceStream, registry.findPublishedStreamForHlsPlayback("live", "browser01"));
            IPublishedStream hlsAudioDerivedByPath = registry.findPublishedStreamByPath("live", "browser01__hls");
            IPublishedStream hlsAudioDerived = registry.findPublishedStreamForHlsAudioPlayback("live", "browser01");
            Assertions.assertNotNull(hlsAudioDerivedByPath);
            Assertions.assertSame(hlsAudioDerivedByPath, hlsAudioDerived);
        } finally {
            orchestrator.close();
        }
    }

    @Test
    void legacyPlaybackShouldKeepSourceStreamForNonWebRtcSource() {
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

            Assertions.assertSame(sourceStream, registry.findPublishedStreamForRtspPlayback("live", "camera"));
            Assertions.assertSame(sourceStream, registry.findPublishedStreamForRtmpPlayback("live", "camera"));
            Assertions.assertSame(sourceStream, registry.findPublishedStreamForHttpFlvPlayback("live", "camera"));
            Assertions.assertSame(sourceStream, registry.findPublishedStreamForHlsPlayback("live", "camera"));
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
            public boolean managesDerivedStream(StreamKey derivedKey) {
                return derivedKey != null && derivedKey.stream() != null && derivedKey.stream().endsWith("__webrtc");
            }

            @Override
            public StreamKey sourceKeyForDerived(StreamKey derivedKey) {
                return new StreamKey(derivedKey.protocol(), derivedKey.app(), "camera");
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

    @Test
    void derivedPlaybackSubscriberTransitionShouldActivateSourcePlaybackAndRequestKeyframe() {
        StreamRegistry registry = new StreamRegistry();
        final StreamKey[] activatedSourceKey = new StreamKey[2];
        final boolean[] activationState = new boolean[2];
        final String[] requestedTrackId = new String[1];
        final int[] activationCallCount = new int[1];
        registry.setStreamTransformOrchestrator(new StreamTransformOrchestrator() {
            @Override
            public void onStreamRegistered(StreamKey sourceKey) {
            }

            @Override
            public void onStreamRemoved(StreamKey sourceKey) {
            }

            @Override
            public void onFrame(InboundMediaFrame frame) {
            }

            @Override
            public boolean requestKeyFrame(StreamKey sourceKey, String trackId) {
                requestedTrackId[0] = trackId;
                return true;
            }

            @Override
            public void setPlaybackActive(StreamKey sourceKey, StreamKey derivedKey, boolean active) {
                int index = activationCallCount[0]++;
                activatedSourceKey[index] = sourceKey;
                activationState[index] = active;
            }

            @Override
            public boolean managesDerivedStream(StreamKey derivedKey) {
                return derivedKey != null && derivedKey.stream() != null && derivedKey.stream().endsWith("__webrtc");
            }

            @Override
            public StreamKey sourceKeyForDerived(StreamKey derivedKey) {
                return new StreamKey(derivedKey.protocol(), derivedKey.app(), "camera");
            }

            @Override
            public boolean shouldRequestKeyFrameOnFirstSubscriber(StreamKey derivedKey) {
                return true;
            }

            @Override
            public void close() {
            }
        });

        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        DefaultPublishedStream sourceStream = new DefaultPublishedStream(sourceKey);
        sourceStream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "source-session",
                sourceKey,
                "video-main",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{0x01}
        ));
        registry.registerPublishedStream(sourceKey, sourceStream);
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        DefaultPublishedStream derivedStream = new DefaultPublishedStream(derivedKey);
        registry.registerPublishedStream(derivedKey, derivedStream);

        MediaSubscriberAdapter subscriber = new MediaSubscriberAdapter() {
            @Override
            public String sessionId() {
                return "sub-1";
            }

            @Override
            public boolean acceptsTrack(String trackId) {
                return true;
            }

            @Override
            public void writeMediaPacket(com.wenting.mediaserver.core.publish.InboundRtpPacket packet) {
            }
        };
        derivedStream.addSubscriber(subscriber);
        derivedStream.removeSubscriber("sub-1");

        Assertions.assertEquals(sourceKey, activatedSourceKey[0]);
        Assertions.assertTrue(activationState[0]);
        Assertions.assertEquals("video-main", requestedTrackId[0]);
        Assertions.assertEquals(sourceKey, activatedSourceKey[1]);
        Assertions.assertFalse(activationState[1]);
    }
}
