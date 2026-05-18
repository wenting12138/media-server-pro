package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.transcode.orchestrator.StreamTransformOrchestrator;
import com.wenting.mediaserver.core.transcode.orchestrator.WebRtcPlaybackStreamTransformOrchestrator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class StreamRegistryWebRtcAudioPlaybackTest {

    @Test
    void shouldPublishSharedDerivedAudioFrames() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamTransformOrchestrator orchestrator = new WebRtcPlaybackStreamTransformOrchestrator(
                registry,
                registry.webRtcPlaybackSuffix()
        );
        registry.setStreamTransformOrchestrator(orchestrator);
        try {
            StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "mic01");
            DefaultPublishedStream sourceStream = new DefaultPublishedStream(sourceKey);
            registry.registerPublishedStream(sourceKey, sourceStream);

            DefaultPublishedStream derivedStream = (DefaultPublishedStream) registry.findPublishedStreamByPath("live", "mic01__webrtc");
            Assertions.assertNotNull(derivedStream);

            RecordingSubscriber subscriber = new RecordingSubscriber();
            derivedStream.addSubscriber(subscriber);

            InboundMediaFrame firstFrame = new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.AUDIO,
                    CodecType.G711U,
                    "publisher",
                    sourceKey,
                    "audio-g711u",
                    Long.valueOf(20L),
                    Long.valueOf(20L),
                    false,
                    false,
                    null,
                    new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55}
            );
            InboundMediaFrame secondFrame = new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.AUDIO,
                    CodecType.G711U,
                    "publisher",
                    sourceKey,
                    "audio-g711u",
                    Long.valueOf(40L),
                    Long.valueOf(40L),
                    false,
                    false,
                    null,
                    new byte[]{0x66, 0x77, 0x21, 0x32}
            );

            sourceStream.onInboundFrame(firstFrame);
            registry.onPublishedFrame(firstFrame);
            sourceStream.onInboundFrame(secondFrame);
            registry.onPublishedFrame(secondFrame);

            waitForFrames(subscriber.frames, 1);

            InboundMediaFrame derivedFrame = subscriber.frames.get(0);
            Assertions.assertEquals(CodecType.G711U, derivedFrame.codecType());
            Assertions.assertEquals("audio-g711u", derivedFrame.trackId());
            Assertions.assertEquals("mic01__webrtc", derivedFrame.streamKey().stream());
            Assertions.assertArrayEquals(secondFrame.payload(), derivedFrame.payload());
        } finally {
            orchestrator.close();
        }
    }

    private void waitForFrames(List<InboundMediaFrame> frames, int expectedSize) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (frames.size() >= expectedSize) {
                return;
            }
            Thread.sleep(25L);
        }
        Assertions.fail("Timed out waiting for derived audio frames, received=" + frames.size());
    }

    private static final class RecordingSubscriber implements MediaSubscriberAdapter {
        private final List<InboundMediaFrame> frames = new CopyOnWriteArrayList<InboundMediaFrame>();

        @Override
        public String sessionId() {
            return "sub-audio-1";
        }

        @Override
        public boolean acceptsTrack(String trackId) {
            return true;
        }

        @Override
        public void writeMediaPacket(InboundRtpPacket packet) {
        }

        @Override
        public void writeInboundFrame(InboundMediaFrame frame) {
            if (frame != null) {
                frames.add(frame);
            }
        }
    }
}
