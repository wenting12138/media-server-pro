package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.transcode.canonical.AudioFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;
import com.wenting.mediaserver.core.transcode.engine.AudioFrameTranscoder;
import com.wenting.mediaserver.core.transcode.engine.AudioFrameTranscoderFactory;
import com.wenting.mediaserver.core.transcode.policy.AudioTransformDecisionPolicy;
import com.wenting.mediaserver.core.transcode.policy.TransformDecision;
import com.wenting.mediaserver.core.transcode.publish.DerivedStreamPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class AudioTranscodeWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscodeWorker.class);
    private static final long REALTIME_STATS_LOG_INTERVAL_MS = 5000L;
    private static final long STALE_AUDIO_FRAME_DROP_THRESHOLD_MS = 200L;

    private final StreamKey sourceKey;
    private final StreamKey derivedKey;
    private final DerivedStreamPublisher publisher;
    private final AudioFrameCanonicalizer canonicalizer;
    private final AudioFrameTranscoderFactory transcoderFactory;
    private final AudioTransformDecisionPolicy decisionPolicy;
    private final ArrayBlockingQueue<CanonicalAudioFrame> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean firstDerivedOutputLogged = new AtomicBoolean(false);
    private final AtomicLong backlogTrimCount = new AtomicLong(0);
    private final AtomicLong staleDropCount = new AtomicLong(0);
    private final AtomicLong queuePeakSinceLastReport = new AtomicLong(0);
    private final AtomicBoolean transcoderResetRequested = new AtomicBoolean(false);
    private final Thread thread;
    private final AtomicBoolean playbackActive = new AtomicBoolean(false);
    private volatile TransformDecision transformDecision = TransformDecision.PENDING;
    private volatile AudioFrameTranscoder transcoder;
    private volatile CanonicalAudioFrame latestStartupConfigFrame;
    private volatile CanonicalAudioFrame latestStartupMediaFrame;
    private volatile long latestObservedMediaTimestampMs = Long.MIN_VALUE;
    private volatile long lastRealtimeStatsLogAtMs = System.currentTimeMillis();
    private volatile long lastReportedBacklogTrimCount = 0L;
    private volatile long lastReportedStaleDropCount = 0L;

    private AudioTranscodeWorker(
            StreamKey sourceKey,
            StreamKey derivedKey,
            DerivedStreamPublisher publisher,
            AudioFrameCanonicalizer canonicalizer,
            AudioFrameTranscoderFactory transcoderFactory,
            AudioTransformDecisionPolicy decisionPolicy,
            int queueSize
    ) {
        this.sourceKey = sourceKey;
        this.derivedKey = derivedKey;
        this.publisher = publisher;
        this.canonicalizer = canonicalizer;
        this.transcoderFactory = transcoderFactory;
        this.decisionPolicy = decisionPolicy;
        this.queue = new ArrayBlockingQueue<CanonicalAudioFrame>(queueSize);
        this.thread = new Thread(this, "audio-transform-" + sourceKey.path().replace('/', '_'));
        this.thread.setDaemon(true);
    }

    static AudioTranscodeWorker start(
            StreamKey sourceKey,
            StreamKey derivedKey,
            DerivedStreamPublisher publisher,
            AudioFrameCanonicalizer canonicalizer,
            AudioFrameTranscoderFactory transcoderFactory,
            AudioTransformDecisionPolicy decisionPolicy,
            int queueSize
    ) {
        AudioTranscodeWorker worker = new AudioTranscodeWorker(
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
        log.debug("Started audio transform worker source={} derived={}", sourceKey, derivedKey);
        return worker;
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        transcoderResetRequested.set(true);
        thread.interrupt();
        canonicalizer.close();
        queue.clear();
        if (publisher != null) {
            publisher.removeStream(derivedKey);
        }
        log.debug("Stopped audio transform worker source={} derived={}", sourceKey, derivedKey);
    }

    void enqueueFrame(InboundMediaFrame frame) {
        if (!running.get() || frame == null) {
            return;
        }
        CanonicalAudioFrame canonicalFrame = canonicalizer.canonicalize(frame);
        if (canonicalFrame != null) {
            rememberStartupFrame(canonicalFrame);
            rememberObservedTimestamp(canonicalFrame);
            if (!playbackActive.get()) {
                return;
            }
            enqueueCanonical(canonicalFrame);
        }
    }

    void enqueuePacket(InboundRtpPacket packet, ITrack track) {
        if (!running.get() || packet == null) {
            return;
        }
        for (CanonicalAudioFrame canonicalFrame : canonicalizer.canonicalize(packet, track)) {
            rememberStartupFrame(canonicalFrame);
            rememberObservedTimestamp(canonicalFrame);
            if (!playbackActive.get()) {
                continue;
            }
            enqueueCanonical(canonicalFrame);
        }
    }

    void setPlaybackActive(boolean active) {
        boolean changed = playbackActive.getAndSet(active) != active;
        if (!changed) {
            return;
        }
        if (!active) {
            queue.clear();
            requestTranscoderReset();
            resetRealtimeStatsWindow();
            thread.interrupt();
            log.info("Suspended audio transform worker source={} derived={}", sourceKey, derivedKey);
            return;
        }
        resetRealtimeStatsWindow();
        seedStartupCache();
        log.info("Activated audio transform worker source={} derived={}", sourceKey, derivedKey);
    }

    private void rememberStartupFrame(CanonicalAudioFrame frame) {
        if (frame == null) {
            return;
        }
        if (frame.configFrame()) {
            latestStartupConfigFrame = frame;
        } else {
            latestStartupMediaFrame = frame;
        }
    }

    private void rememberObservedTimestamp(CanonicalAudioFrame frame) {
        Long timestampMs = mediaTimestampMillis(frame);
        if (timestampMs == null) {
            return;
        }
        if (timestampMs.longValue() > latestObservedMediaTimestampMs) {
            latestObservedMediaTimestampMs = timestampMs.longValue();
        }
    }

    private void seedStartupCache() {
        CanonicalAudioFrame configFrame = latestStartupConfigFrame;
        CanonicalAudioFrame mediaFrame = latestStartupMediaFrame;
        int seeded = 0;
        if (configFrame != null) {
            queue.offer(configFrame);
            seeded++;
        }
        if (mediaFrame != null && mediaFrame != configFrame) {
            queue.offer(mediaFrame);
            seeded++;
        }
        if (seeded > 0) {
            observeQueueDepth();
            log.info("Seeded audio startup cache source={} derived={} seededFrames={} hasConfig={} hasMedia={}",
                    sourceKey, derivedKey, seeded, configFrame != null, mediaFrame != null);
        }
    }

    private void enqueueCanonical(CanonicalAudioFrame canonicalFrame) {
        if (canonicalFrame == null) {
            return;
        }
        if (!queue.offer(canonicalFrame)) {
            trimBacklogForRealtime(canonicalFrame);
            observeQueueDepth();
            return;
        }
        observeQueueDepth();
    }

    private void trimBacklogForRealtime(CanonicalAudioFrame incomingFrame) {
        CanonicalAudioFrame latestConfigFrame = null;
        int dropped = 0;
        CanonicalAudioFrame queuedFrame;
        while ((queuedFrame = queue.poll()) != null) {
            dropped++;
            if (queuedFrame.configFrame()) {
                latestConfigFrame = queuedFrame;
            }
        }
        if (latestConfigFrame != null && latestConfigFrame != incomingFrame && !incomingFrame.configFrame()) {
            queue.offer(latestConfigFrame);
        }
        queue.offer(incomingFrame);
        long trim = backlogTrimCount.incrementAndGet();
        if (trim == 1 || trim % 20 == 0) {
            log.warn(
                    "Trimmed audio transform backlog source={} derived={} droppedFrames={} queueSize={} incomingConfig={}",
                    sourceKey,
                    derivedKey,
                    dropped,
                    queue.size(),
                    incomingFrame.configFrame()
            );
        }
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                applyPendingTranscoderReset();
                try {
                    CanonicalAudioFrame frame = queue.poll(1000L, TimeUnit.MILLISECONDS);
                    maybeLogRealtimeStats();
                    applyPendingTranscoderReset();
                    if (frame == null) {
                        continue;
                    }
                    if (!playbackActive.get()) {
                        continue;
                    }
                    if (shouldDropStaleFrame(frame)) {
                        continue;
                    }
                    TransformDecision nextDecision = decisionPolicy == null
                            ? TransformDecision.TRANSCODE
                            : decisionPolicy.decide(sourceKey, frame, transformDecision);
                    if (nextDecision != transformDecision) {
                        log.info(
                                "Audio transform decision source={} derived={} decision={} codec={} configFrame={}",
                                sourceKey,
                                derivedKey,
                                nextDecision,
                                frame.codecType(),
                                frame.configFrame()
                        );
                        transformDecision = nextDecision;
                    }
                    if (transformDecision == TransformDecision.PENDING) {
                        continue;
                    }
                    if (transformDecision == TransformDecision.PASSTHROUGH) {
                        if (playbackActive.get()) {
                            publisher.publish(derivedKey, copyAsDerivedFrame(frame));
                        }
                        continue;
                    }
                    AudioFrameTranscoder activeTranscoder = ensureTranscoder();
                    if (activeTranscoder == null) {
                        continue;
                    }
                    List<InboundMediaFrame> outputs = activeTranscoder.transcode(frame, derivedKey);
                    if (!playbackActive.get()) {
                        continue;
                    }
                    for (InboundMediaFrame output : outputs) {
                        logFirstDerivedOutput(frame, output);
                        publisher.publish(derivedKey, output);
                    }
                } catch (InterruptedException e) {
                    if (!running.get()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Audio transform failed source={} derived={}", sourceKey, derivedKey, e);
                }
            }
        } finally {
            closeTranscoder();
        }
    }

    private boolean shouldDropStaleFrame(CanonicalAudioFrame frame) {
        if (frame == null || frame.configFrame()) {
            return false;
        }
        Long frameTimestampMs = mediaTimestampMillis(frame);
        long latestTimestampMs = latestObservedMediaTimestampMs;
        if (frameTimestampMs == null || latestTimestampMs == Long.MIN_VALUE) {
            return false;
        }
        long lagMs = latestTimestampMs - frameTimestampMs.longValue();
        if (lagMs <= STALE_AUDIO_FRAME_DROP_THRESHOLD_MS) {
            return false;
        }
        long count = staleDropCount.incrementAndGet();
        if (count == 1 || count % 20 == 0) {
            log.warn("Dropped stale audio transform frame source={} derived={} lagMs={} dropped={} configFrame={}",
                    sourceKey, derivedKey, lagMs, count, frame.configFrame());
        }
        return true;
    }

    private void observeQueueDepth() {
        long depth = queue.size();
        long observed;
        do {
            observed = queuePeakSinceLastReport.get();
            if (depth <= observed) {
                return;
            }
        } while (!queuePeakSinceLastReport.compareAndSet(observed, depth));
    }

    private void maybeLogRealtimeStats() {
        if (!usesWebRtcRealtimeStats() || !playbackActive.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRealtimeStatsLogAtMs < REALTIME_STATS_LOG_INTERVAL_MS) {
            return;
        }
        long trimCount = backlogTrimCount.get();
        long staleCount = staleDropCount.get();
        long trimDelta = trimCount - lastReportedBacklogTrimCount;
        long staleDelta = staleCount - lastReportedStaleDropCount;
        int queueCurrent = queue.size();
        long queuePeak = Math.max(queueCurrent, queuePeakSinceLastReport.getAndSet(queueCurrent));
        log.info(
                "WebRTC audio transform realtime stats source={} derived={} decision={} queueCurrent={} queuePeak={} staleDrops={} trimCount={}",
                sourceKey,
                derivedKey,
                transformDecision,
                queueCurrent,
                queuePeak,
                staleDelta,
                trimDelta
        );
        lastRealtimeStatsLogAtMs = now;
        lastReportedBacklogTrimCount = trimCount;
        lastReportedStaleDropCount = staleCount;
    }

    private void requestTranscoderReset() {
        transcoderResetRequested.set(true);
    }

    private void applyPendingTranscoderReset() {
        if (!transcoderResetRequested.compareAndSet(true, false)) {
            return;
        }
        closeTranscoder();
    }

    private void resetRealtimeStatsWindow() {
        queuePeakSinceLastReport.set(queue.size());
        lastRealtimeStatsLogAtMs = System.currentTimeMillis();
        lastReportedBacklogTrimCount = backlogTrimCount.get();
        lastReportedStaleDropCount = staleDropCount.get();
    }

    private Long mediaTimestampMillis(CanonicalAudioFrame frame) {
        if (frame == null || frame.sourceFrame() == null) {
            return null;
        }
        InboundMediaFrame sourceFrame = frame.sourceFrame();
        return sourceFrame.ptsMillis() == null ? sourceFrame.dtsMillis() : sourceFrame.ptsMillis();
    }

    private InboundMediaFrame copyAsDerivedFrame(CanonicalAudioFrame frame) {
        InboundMediaFrame sourceFrame = frame == null ? null : frame.sourceFrame();
        if (sourceFrame == null || frame == null) {
            return null;
        }
        return new InboundMediaFrame(
                sourceFrame.sourceProtocol(),
                sourceFrame.trackType(),
                frame.codecType(),
                sourceFrame.sessionId(),
                derivedKey,
                sourceFrame.trackId(),
                sourceFrame.ptsMillis(),
                sourceFrame.dtsMillis(),
                sourceFrame.keyFrame(),
                frame.configFrame(),
                sourceFrame.outOfBandParameterSetsReady(),
                sourceFrame.remoteAddress(),
                frame.payload()
        );
    }

    private AudioFrameTranscoder ensureTranscoder() {
        AudioFrameTranscoder existing = transcoder;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (transcoder == null && transcoderFactory != null) {
                transcoder = transcoderFactory.create();
                log.info("Initialized audio transcoder source={} derived={}", sourceKey, derivedKey);
            }
            return transcoder;
        }
    }

    private void logFirstDerivedOutput(CanonicalAudioFrame input, InboundMediaFrame output) {
        if (input == null || output == null) {
            return;
        }
        if (!firstDerivedOutputLogged.compareAndSet(false, true)) {
            return;
        }
        log.info("First derived audio frame source={} derived={} inputCodec={} outputCodec={} outputTrack={} bytes={} configFrame={}",
                sourceKey,
                derivedKey,
                input.codecType(),
                output.codecType(),
                output.trackId(),
                output.payloadLength(),
                output.configFrame());
    }

    private void closeTranscoder() {
        AudioFrameTranscoder existing = transcoder;
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

    private boolean usesWebRtcRealtimeStats() {
        return sourceKey != null && sourceKey.protocol() == StreamProtocol.WEBRTC;
    }
}
