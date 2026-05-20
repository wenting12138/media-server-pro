package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;
import com.wenting.mediaserver.core.transcode.canonical.H264CodecConfig;
import com.wenting.mediaserver.core.transcode.canonical.VideoFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.VideoPayloadFormat;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoder;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoderFactory;
import com.wenting.mediaserver.core.transcode.policy.TransformDecision;
import com.wenting.mediaserver.core.transcode.policy.TranscodeDecisionPolicy;
import com.wenting.mediaserver.core.transcode.publish.DerivedStreamPublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class TranscodeWorkerRealtimeQueueTest {

    @Test
    void shouldTrimBacklogAndKeepLatestFrameWhenTranscoderFallsBehind() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new IdentityCanonicalizer(),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysTranscodeDecisionPolicy(),
                3
        );
        try {
            worker.setPlaybackActive(true);
            worker.enqueueFrame(frame(0, true, true));
            worker.enqueueFrame(frame(1, true, false));
            for (int i = 2; i <= 10; i++) {
                worker.enqueueFrame(frame(i, false, false));
            }

            waitForLastPayload(publisher.frames(), (byte) 10, 5000L);

            List<InboundMediaFrame> outputs = publisher.frames();
            Assertions.assertFalse(outputs.isEmpty());
            Assertions.assertTrue(outputs.size() < 11, "expected aggressive backlog trimming");
            Assertions.assertTrue(containsPayload(outputs, (byte) 10), "latest frame should survive trimming");
        } finally {
            worker.stop();
        }
    }

    @Test
    void shouldNotPublishWhenPlaybackIsInactive() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new IdentityCanonicalizer(),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysTranscodeDecisionPolicy(),
                3
        );
        try {
            worker.enqueueFrame(frame(0, true, true));
            worker.enqueueFrame(frame(1, true, false));
            Thread.sleep(300L);
            Assertions.assertTrue(publisher.frames().isEmpty());
        } finally {
            worker.stop();
        }
    }

    @Test
    void shouldSeedStartupCacheWhenPlaybackBecomesActive() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new IdentityCanonicalizer(),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysPassthroughDecisionPolicy(),
                3
        );
        try {
            worker.enqueueFrame(frame(0, true, true));
            worker.enqueueFrame(frame(1, true, false));
            Thread.sleep(200L);
            Assertions.assertTrue(publisher.frames().isEmpty());

            worker.setPlaybackActive(true);
            waitForLastPayload(publisher.frames(), (byte) 1, 5000L);

            List<InboundMediaFrame> outputs = publisher.frames();
            Assertions.assertTrue(containsPayload(outputs, (byte) 0));
            Assertions.assertTrue(containsPayload(outputs, (byte) 1));
        } finally {
            worker.stop();
        }
    }

    @Test
    void shouldPublishCanonicalPayloadDuringPassthrough() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.WEBRTC, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.WEBRTC, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new RewritingCanonicalizer(new byte[]{0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33}),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysPassthroughDecisionPolicy(),
                3
        );
        try {
            worker.enqueueFrame(frame(0, true, true));
            worker.enqueueFrame(frame(1, true, false));
            worker.setPlaybackActive(true);

            waitForFrameCount(publisher.frames(), 2, 5000L);

            List<InboundMediaFrame> outputs = publisher.frames();
            Assertions.assertFalse(outputs.isEmpty());
            InboundMediaFrame mediaFrame = outputs.get(outputs.size() - 1);
            Assertions.assertArrayEquals(new byte[]{0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33}, mediaFrame.payload());
            Assertions.assertEquals(derivedKey, mediaFrame.streamKey());
            Assertions.assertTrue(mediaFrame.keyFrame());
            Assertions.assertFalse(mediaFrame.configFrame());
        } finally {
            worker.stop();
        }
    }

    @Test
    void shouldDropStaleIntermediateFramesToFavorRealtime() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.RTMP, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.RTMP, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new IdentityCanonicalizer(),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysTranscodeDecisionPolicy(),
                10
        );
        try {
            worker.setPlaybackActive(true);
            worker.enqueueFrame(frame(0, true, true, 0L));
            worker.enqueueFrame(frame(1, true, false, 40L));
            worker.enqueueFrame(frame(2, false, false, 2000L));
            worker.enqueueFrame(frame(3, false, false, 4000L));

            waitForLastPayload(publisher.frames(), (byte) 3, 5000L);

            List<InboundMediaFrame> outputs = publisher.frames();
            Assertions.assertTrue(containsPayload(outputs, (byte) 0));
            Assertions.assertTrue(containsPayload(outputs, (byte) 1));
            Assertions.assertFalse(containsPayload(outputs, (byte) 2), "stale frame should be dropped");
            Assertions.assertTrue(containsPayload(outputs, (byte) 3));
        } finally {
            worker.stop();
        }
    }

    @Test
    void shouldDropStaleIntermediateFramesMoreAggressivelyForWebRtcSource() throws Exception {
        StreamKey sourceKey = new StreamKey(StreamProtocol.WEBRTC, "live", "camera");
        StreamKey derivedKey = new StreamKey(StreamProtocol.WEBRTC, "live", "camera__webrtc");
        CollectingPublisher publisher = new CollectingPublisher();
        TranscodeWorker worker = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                new IdentityCanonicalizer(),
                new SlowPassthroughTranscoderFactory(),
                new AlwaysTranscodeDecisionPolicy(),
                10
        );
        try {
            worker.setPlaybackActive(true);
            worker.enqueueFrame(frame(StreamProtocol.WEBRTC, 0, true, true, 0L));
            worker.enqueueFrame(frame(StreamProtocol.WEBRTC, 1, true, false, 40L));
            worker.enqueueFrame(frame(StreamProtocol.WEBRTC, 2, false, false, 600L));
            worker.enqueueFrame(frame(StreamProtocol.WEBRTC, 3, false, false, 1200L));

            waitForLastPayload(publisher.frames(), (byte) 3, 5000L);

            List<InboundMediaFrame> outputs = publisher.frames();
            Assertions.assertTrue(containsPayload(outputs, (byte) 0));
            Assertions.assertTrue(containsPayload(outputs, (byte) 1));
            Assertions.assertFalse(containsPayload(outputs, (byte) 2), "webrtc source should drop stale frame earlier");
            Assertions.assertTrue(containsPayload(outputs, (byte) 3));
        } finally {
            worker.stop();
        }
    }

    private static void waitForLastPayload(List<InboundMediaFrame> outputs, byte payloadByte, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (containsPayload(outputs, payloadByte)) {
                return;
            }
            Thread.sleep(50L);
        }
        Assertions.fail("Timed out waiting for payload byte " + payloadByte);
    }

    private static void waitForFrameCount(List<InboundMediaFrame> outputs, int expectedCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (outputs.size() >= expectedCount) {
                return;
            }
            Thread.sleep(50L);
        }
        Assertions.fail("Timed out waiting for frame count " + expectedCount + ", actual=" + outputs.size());
    }

    private static boolean containsPayload(List<InboundMediaFrame> outputs, byte payloadByte) {
        for (InboundMediaFrame frame : outputs) {
            if (frame != null && frame.payload() != null && frame.payloadLength() > 0 && frame.payload()[0] == payloadByte) {
                return true;
            }
        }
        return false;
    }

    private static InboundMediaFrame frame(int index, boolean keyFrame, boolean configFrame) {
        return frame(index, keyFrame, configFrame, (long) index);
    }

    private static InboundMediaFrame frame(int index, boolean keyFrame, boolean configFrame, long timestampMs) {
        return frame(StreamProtocol.RTMP, index, keyFrame, configFrame, timestampMs);
    }

    private static InboundMediaFrame frame(StreamProtocol protocol, int index, boolean keyFrame, boolean configFrame, long timestampMs) {
        return new InboundMediaFrame(
                protocol,
                TrackType.VIDEO,
                CodecType.H264,
                "session-" + index,
                new StreamKey(protocol, "live", "camera"),
                "video-h264",
                Long.valueOf(timestampMs),
                Long.valueOf(timestampMs),
                keyFrame,
                configFrame,
                null,
                new byte[]{(byte) index}
        );
    }

    private static final class CollectingPublisher implements DerivedStreamPublisher {

        private final List<InboundMediaFrame> frames = new CopyOnWriteArrayList<InboundMediaFrame>();

        @Override
        public DefaultPublishedStream ensureStream(StreamKey derivedKey) {
            return new DefaultPublishedStream(derivedKey);
        }

        @Override
        public void publish(StreamKey derivedKey, InboundMediaFrame frame) {
            if (frame != null) {
                frames.add(frame);
            }
        }

        @Override
        public void removeStream(StreamKey derivedKey) {
        }

        List<InboundMediaFrame> frames() {
            return frames;
        }
    }

    private static final class IdentityCanonicalizer implements VideoFrameCanonicalizer {

        @Override
        public CanonicalVideoFrame canonicalize(InboundMediaFrame frame) {
            return new CanonicalVideoFrame(
                    frame,
                    VideoPayloadFormat.H264_AVCC,
                    frame.payload(),
                    frame.keyFrame(),
                    frame.configFrame(),
                    new H264CodecConfig(4, new byte[]{0x67, 0x42, 0x00, 0x1f}, new byte[]{0x68, 0x00}, "42001f")
            );
        }

        @Override
        public void close() {
        }
    }

    private static final class RewritingCanonicalizer implements VideoFrameCanonicalizer {

        private final byte[] canonicalPayload;

        private RewritingCanonicalizer(byte[] canonicalPayload) {
            this.canonicalPayload = canonicalPayload;
        }

        @Override
        public CanonicalVideoFrame canonicalize(InboundMediaFrame frame) {
            return new CanonicalVideoFrame(
                    frame,
                    VideoPayloadFormat.H264_AVCC,
                    canonicalPayload,
                    frame.keyFrame(),
                    frame.configFrame(),
                    new H264CodecConfig(4, new byte[]{0x67, 0x42, 0x00, 0x1f}, new byte[]{0x68, 0x00}, "42001f")
            );
        }

        @Override
        public void close() {
        }
    }

    private static final class SlowPassthroughTranscoderFactory implements VideoFrameTranscoderFactory {

        @Override
        public VideoFrameTranscoder create() {
            return new VideoFrameTranscoder() {
                @Override
                public List<InboundMediaFrame> transcode(CanonicalVideoFrame frame, StreamKey derivedKey) {
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return java.util.Collections.singletonList(frame.sourceFrame());
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class AlwaysTranscodeDecisionPolicy implements TranscodeDecisionPolicy {

        @Override
        public TransformDecision decide(StreamKey sourceKey, CanonicalVideoFrame frame, TransformDecision currentDecision) {
            return TransformDecision.TRANSCODE;
        }
    }

    private static final class AlwaysPassthroughDecisionPolicy implements TranscodeDecisionPolicy {

        @Override
        public TransformDecision decide(StreamKey sourceKey, CanonicalVideoFrame frame, TransformDecision currentDecision) {
            return TransformDecision.PASSTHROUGH;
        }
    }
}
