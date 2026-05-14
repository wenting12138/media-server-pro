package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.transcode.canonical.RtmpAvccH264Canonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.RtspRtpH264Canonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.VideoFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.engine.H264Avcc420pTranscoder;
import com.wenting.mediaserver.core.transcode.policy.WebRtcH264ProfileDecisionPolicy;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.transcode.publish.DefaultDerivedStreamPublisher;
import com.wenting.mediaserver.core.transcode.publish.DerivedStreamPublisher;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebRtcPlaybackStreamTransformOrchestrator implements StreamTransformOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WebRtcPlaybackStreamTransformOrchestrator.class);
    private static final int DEFAULT_QUEUE_SIZE = 128;

    private final com.wenting.mediaserver.core.registry.StreamRegistry registry;
    private final DerivedStreamPublisher publisher;
    private final String playbackSuffix;
    private final int queueSize;
    private final Map<StreamKey, TranscodeWorker> workersBySourceKey =
            new ConcurrentHashMap<StreamKey, TranscodeWorker>();

    public WebRtcPlaybackStreamTransformOrchestrator(
            com.wenting.mediaserver.core.registry.StreamRegistry registry,
            String playbackSuffix
    ) {
        this(registry, new DefaultDerivedStreamPublisher(registry), playbackSuffix, DEFAULT_QUEUE_SIZE);
    }

    private WebRtcPlaybackStreamTransformOrchestrator(
            com.wenting.mediaserver.core.registry.StreamRegistry registry,
            DerivedStreamPublisher publisher,
            String playbackSuffix,
            int queueSize
    ) {
        this.registry = registry;
        this.publisher = publisher;
        this.playbackSuffix = playbackSuffix == null || playbackSuffix.trim().isEmpty()
                ? "__webrtc"
                : playbackSuffix.trim();
        this.queueSize = queueSize <= 0 ? DEFAULT_QUEUE_SIZE : queueSize;
    }

    @Override
    public void onStreamRegistered(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return;
        }
        ensureWorker(sourceKey);
    }

    @Override
    public void onStreamRemoved(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return;
        }
        TranscodeWorker worker = workersBySourceKey.remove(sourceKey);
        if (worker != null) {
            worker.stop();
        }
    }

    @Override
    public void onFrame(InboundMediaFrame frame) {
        if (frame == null || frame.streamKey() == null) {
            return;
        }
        if (frame.trackType() != TrackType.VIDEO || frame.codecType() != CodecType.H264) {
            return;
        }
        TranscodeWorker worker = ensureWorker(frame.streamKey());
        if (worker != null) {
            worker.enqueueFrame(frame);
        }
    }

    @Override
    public void onPacket(InboundRtpPacket packet) {
        if (packet == null || packet.frame() == null || packet.frame().streamKey() == null) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
        if (frame.trackType() != TrackType.VIDEO || frame.codecType() != CodecType.H264) {
            return;
        }
        TranscodeWorker worker = ensureWorker(frame.streamKey());
        if (worker == null) {
            return;
        }
        worker.enqueuePacket(packet, resolveTrack(frame));
    }

    @Override
    public void close() {
        for (TranscodeWorker worker : workersBySourceKey.values()) {
            if (worker != null) {
                worker.stop();
            }
        }
        workersBySourceKey.clear();
    }

    private TranscodeWorker ensureWorker(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return null;
        }
        TranscodeWorker worker = workersBySourceKey.get(sourceKey);
        if (worker != null) {
            return worker;
        }
        StreamKey derivedKey = new StreamKey(sourceKey.protocol(), sourceKey.app(), sourceKey.stream() + playbackSuffix);
        VideoFrameCanonicalizer canonicalizer = buildCanonicalizer(sourceKey);
        if (canonicalizer == null) {
            return null;
        }
        TranscodeWorker created = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                canonicalizer,
                new H264Avcc420pTranscoder(),
                new WebRtcH264ProfileDecisionPolicy(),
                queueSize
        );
        TranscodeWorker existing = workersBySourceKey.putIfAbsent(sourceKey, created);
        if (existing != null) {
            created.stop();
            return existing;
        }
        log.debug("Created stream transform worker source={} derived={}", sourceKey, derivedKey);
        return created;
    }

    private VideoFrameCanonicalizer buildCanonicalizer(StreamKey sourceKey) {
        StreamProtocol protocol = sourceKey == null ? StreamProtocol.UNKNOWN : sourceKey.protocol();
        if (protocol == StreamProtocol.RTMP) {
            return new RtmpAvccH264Canonicalizer();
        }
        if (protocol == StreamProtocol.RTSP) {
            return new RtspRtpH264Canonicalizer();
        }
        log.debug("No video frame canonicalizer registered yet for protocol={} source={}", protocol, sourceKey);
        return null;
    }

    private ITrack resolveTrack(InboundMediaFrame frame) {
        if (registry == null || registry.getRtspSessionManager() == null || frame == null || frame.sessionId() == null) {
            return null;
        }
        RtspSession session =
                registry.getRtspSessionManager().find(frame.sessionId());
        return session == null ? null : session.findTrack(frame.trackId());
    }

    private boolean isDerivedStream(StreamKey key) {
        return key != null && key.stream() != null && key.stream().endsWith(playbackSuffix);
    }
}
