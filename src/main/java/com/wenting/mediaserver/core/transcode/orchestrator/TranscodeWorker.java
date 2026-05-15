package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;
import com.wenting.mediaserver.core.transcode.canonical.VideoFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoder;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoderFactory;
import com.wenting.mediaserver.core.transcode.policy.TransformDecision;
import com.wenting.mediaserver.core.transcode.policy.TranscodeDecisionPolicy;
import com.wenting.mediaserver.core.transcode.publish.DerivedStreamPublisher;
import com.wenting.mediaserver.core.track.ITrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TranscodeWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorker.class);

    private final StreamKey sourceKey;
    private final StreamKey derivedKey;
    private final DerivedStreamPublisher publisher;
    private final VideoFrameCanonicalizer canonicalizer;
    private final VideoFrameTranscoderFactory transcoderFactory;
    private final TranscodeDecisionPolicy decisionPolicy;
    private final ArrayBlockingQueue<CanonicalVideoFrame> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private volatile TransformDecision transformDecision = TransformDecision.PENDING;
    private volatile VideoFrameTranscoder transcoder;

    private TranscodeWorker(
            StreamKey sourceKey,
            StreamKey derivedKey,
            DerivedStreamPublisher publisher,
            VideoFrameCanonicalizer canonicalizer,
            VideoFrameTranscoderFactory transcoderFactory,
            TranscodeDecisionPolicy decisionPolicy,
            int queueSize
    ) {
        this.sourceKey = sourceKey;
        this.derivedKey = derivedKey;
        this.publisher = publisher;
        this.canonicalizer = canonicalizer;
        this.transcoderFactory = transcoderFactory;
        this.decisionPolicy = decisionPolicy;
        this.queue = new ArrayBlockingQueue<CanonicalVideoFrame>(queueSize);
        this.thread = new Thread(this, "stream-transform-" + sourceKey.path().replace('/', '_'));
        this.thread.setDaemon(true);
    }

    static TranscodeWorker start(
            StreamKey sourceKey,
            StreamKey derivedKey,
            DerivedStreamPublisher publisher,
            VideoFrameCanonicalizer canonicalizer,
            VideoFrameTranscoderFactory transcoderFactory,
            TranscodeDecisionPolicy decisionPolicy,
            int queueSize
    ) {
        TranscodeWorker worker = new TranscodeWorker(
                sourceKey,
                derivedKey,
                publisher,
                canonicalizer,
                transcoderFactory,
                decisionPolicy,
                queueSize
        );
        if (publisher != null) {
            publisher.ensureStream(derivedKey);
        }
        worker.thread.start();
        log.info("Started stream transform worker source={} derived={}", sourceKey, derivedKey);
        return worker;
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        canonicalizer.close();
        closeTranscoder();
        queue.clear();
        if (publisher != null) {
            publisher.removeStream(derivedKey);
        }
        log.info("Stopped stream transform worker source={} derived={}", sourceKey, derivedKey);
    }

    void enqueueFrame(InboundMediaFrame frame) {
        if (!running.get() || frame == null) {
            return;
        }
        CanonicalVideoFrame canonicalFrame = canonicalizer.canonicalize(frame);
        if (canonicalFrame == null) {
            return;
        }
        enqueueCanonical(canonicalFrame);
    }

    void enqueuePacket(InboundRtpPacket packet, ITrack track) {
        if (!running.get() || packet == null) {
            return;
        }
        for (CanonicalVideoFrame canonicalFrame : canonicalizer.canonicalize(packet, track)) {
            enqueueCanonical(canonicalFrame);
        }
    }

    private void enqueueCanonical(CanonicalVideoFrame canonicalFrame) {
        if (canonicalFrame == null) {
            return;
        }
        if (!queue.offer(canonicalFrame)) {
            queue.poll();
            queue.offer(canonicalFrame);
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                CanonicalVideoFrame frame = queue.poll(1000L, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                TransformDecision nextDecision = decisionPolicy == null
                        ? TransformDecision.TRANSCODE
                        : decisionPolicy.decide(sourceKey, frame, transformDecision);
                if (nextDecision != transformDecision) {
                    log.info(
                            "Stream transform decision source={} derived={} decision={} profile-level-id={} keyFrame={} configFrame={}",
                            sourceKey,
                            derivedKey,
                            nextDecision,
                            frame.h264CodecConfig() == null ? null : frame.h264CodecConfig().profileLevelId(),
                            frame.keyFrame(),
                            frame.configFrame()
                    );
                    transformDecision = nextDecision;
                }
                if (transformDecision == TransformDecision.PENDING) {
                    continue;
                }
                if (transformDecision == TransformDecision.PASSTHROUGH) {
                    publisher.publish(derivedKey, copyAsDerivedFrame(frame.sourceFrame()));
                    continue;
                }
                VideoFrameTranscoder activeTranscoder = ensureTranscoder();
                if (activeTranscoder == null) {
                    continue;
                }
                List<InboundMediaFrame> outputs = activeTranscoder.transcode(frame, derivedKey);
                for (InboundMediaFrame output : outputs) {
                    publisher.publish(derivedKey, output);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Stream transform failed source={} derived={}", sourceKey, derivedKey, e);
            }
        }
    }

    private InboundMediaFrame copyAsDerivedFrame(InboundMediaFrame sourceFrame) {
        if (sourceFrame == null) {
            return null;
        }
        return new InboundMediaFrame(
                sourceFrame.sourceProtocol(),
                sourceFrame.trackType(),
                sourceFrame.codecType(),
                sourceFrame.sessionId(),
                derivedKey,
                sourceFrame.trackId(),
                sourceFrame.ptsMillis(),
                sourceFrame.dtsMillis(),
                sourceFrame.keyFrame(),
                sourceFrame.configFrame(),
                sourceFrame.outOfBandParameterSetsReady(),
                sourceFrame.remoteAddress(),
            sourceFrame.payload()
        );
    }

    private VideoFrameTranscoder ensureTranscoder() {
        VideoFrameTranscoder existing = transcoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (transcoder == null && transcoderFactory != null) {
                transcoder = transcoderFactory.create();
                log.info("Initialized stream transcoder source={} derived={}", sourceKey, derivedKey);
            }
            return transcoder;
        }
    }

    private void closeTranscoder() {
        VideoFrameTranscoder existing = transcoder;
        if (existing == null) {
            return;
        }
        synchronized (this) {
            if (transcoder != null) {
                transcoder.close();
                transcoder = null;
            }
        }
    }
}
