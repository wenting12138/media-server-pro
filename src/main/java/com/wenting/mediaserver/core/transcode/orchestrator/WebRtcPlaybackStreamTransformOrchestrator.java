package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.transcode.canonical.AudioFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.RtmpAvccH264Canonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.RtmpAudioCanonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.RtspRtpH264Canonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.RtspRtpAudioCanonicalizer;
import com.wenting.mediaserver.core.transcode.canonical.VideoFrameCanonicalizer;
import com.wenting.mediaserver.core.transcode.engine.AudioFrameTranscoder;
import com.wenting.mediaserver.core.transcode.engine.AudioFrameTranscoderFactory;
import com.wenting.mediaserver.core.transcode.engine.AudioToG711UTranscoder;
import com.wenting.mediaserver.core.transcode.engine.H264Avcc420pTranscoder;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoder;
import com.wenting.mediaserver.core.transcode.engine.VideoFrameTranscoderFactory;
import com.wenting.mediaserver.core.transcode.policy.WebRtcAudioCodecDecisionPolicy;
import com.wenting.mediaserver.core.transcode.policy.WebRtcH264ProfileDecisionPolicy;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.transcode.publish.DefaultDerivedStreamPublisher;
import com.wenting.mediaserver.core.transcode.publish.DerivedStreamPublisher;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtmp.RtmpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebRtcPlaybackStreamTransformOrchestrator implements StreamTransformOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WebRtcPlaybackStreamTransformOrchestrator.class);
    private static final int DEFAULT_VIDEO_QUEUE_SIZE = 6;
    private static final int DEFAULT_AUDIO_QUEUE_SIZE = 12;

    private final StreamRegistry registry;
    private final DerivedStreamPublisher publisher;
    private final String playbackSuffix;
    private final int videoQueueSize;
    private final int audioQueueSize;
    private final Map<StreamKey, TranscodeWorker> videoWorkersBySourceKey =
            new ConcurrentHashMap<StreamKey, TranscodeWorker>();
    private final Map<StreamKey, AudioTranscodeWorker> audioWorkersBySourceKey =
            new ConcurrentHashMap<StreamKey, AudioTranscodeWorker>();

    public WebRtcPlaybackStreamTransformOrchestrator(
            StreamRegistry registry,
            String playbackSuffix
    ) {
        this(
                registry,
                new DefaultDerivedStreamPublisher(registry),
                playbackSuffix,
                DEFAULT_VIDEO_QUEUE_SIZE,
                DEFAULT_AUDIO_QUEUE_SIZE
        );
    }

    private WebRtcPlaybackStreamTransformOrchestrator(
            StreamRegistry registry,
            DerivedStreamPublisher publisher,
            String playbackSuffix,
            int videoQueueSize,
            int audioQueueSize
    ) {
        this.registry = registry;
        this.publisher = publisher;
        this.playbackSuffix = playbackSuffix == null || playbackSuffix.trim().isEmpty()
                ? "__webrtc"
                : playbackSuffix.trim();
        this.videoQueueSize = videoQueueSize <= 0 ? DEFAULT_VIDEO_QUEUE_SIZE : videoQueueSize;
        this.audioQueueSize = audioQueueSize <= 0 ? DEFAULT_AUDIO_QUEUE_SIZE : audioQueueSize;
    }

    @Override
    public void onStreamRegistered(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return;
        }
        ensureVideoWorker(sourceKey);
        ensureAudioWorker(sourceKey);
    }

    @Override
    public void onStreamRemoved(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return;
        }
        TranscodeWorker videoWorker = videoWorkersBySourceKey.remove(sourceKey);
        if (videoWorker != null) {
            videoWorker.stop();
        }
        AudioTranscodeWorker audioWorker = audioWorkersBySourceKey.remove(sourceKey);
        if (audioWorker != null) {
            audioWorker.stop();
        }
    }

    @Override
    public void onFrame(InboundMediaFrame frame) {
        if (frame == null || frame.streamKey() == null) {
            return;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            if (isSupportedAudioFrame(frame)) {
                AudioTranscodeWorker worker = ensureAudioWorker(frame.streamKey());
                if (worker != null) {
                    worker.enqueueFrame(frame);
                }
            }
        }
        if (frame.trackType() == TrackType.VIDEO) {
            TranscodeWorker worker = ensureVideoWorker(frame.streamKey());
            if (worker != null) {
                worker.enqueueFrame(frame);
            }
        }
    }

    @Override
    public void onPacket(InboundRtpPacket packet) {
        if (packet == null || packet.frame() == null || packet.frame().streamKey() == null) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
        if (frame.trackType() != TrackType.VIDEO || frame.codecType() != CodecType.H264) {
            if (isSupportedAudioFrame(frame)) {
                AudioTranscodeWorker worker = ensureAudioWorker(frame.streamKey());
                if (worker != null) {
                    worker.enqueuePacket(packet, resolveTrack(frame));
                }
            }
            return;
        }
        TranscodeWorker worker = ensureVideoWorker(frame.streamKey());
        if (worker == null) {
            return;
        }
        worker.enqueuePacket(packet, resolveTrack(frame));
    }

    @Override
    public void close() {
        for (TranscodeWorker worker : videoWorkersBySourceKey.values()) {
            if (worker != null) {
                worker.stop();
            }
        }
        videoWorkersBySourceKey.clear();
        for (AudioTranscodeWorker worker : audioWorkersBySourceKey.values()) {
            if (worker != null) {
                worker.stop();
            }
        }
        audioWorkersBySourceKey.clear();
    }

    @Override
    public boolean requestKeyFrame(StreamKey sourceKey, String trackId) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return false;
        }
        TranscodeWorker worker = ensureVideoWorker(sourceKey);
        boolean accepted = false;
        if (worker != null) {
            accepted = worker.requestKeyFrame(trackId);
        }
        if (sourceKey.protocol() == StreamProtocol.RTSP && registry != null && registry.getRtspSessionManager() != null) {
            RtspSession session = registry.getRtspSessionManager().findByStreamKey(sourceKey);
            if (session != null) {
                long mediaSsrc = resolveLatestTrackSsrc(sourceKey, trackId);
                accepted = session.requestVideoKeyFrame(trackId, mediaSsrc) || accepted;
            }
        }
        if (sourceKey.protocol() == StreamProtocol.RTMP && registry != null && registry.getRtmpSessionManager() != null) {
            RtmpSession session = registry.getRtmpSessionManager().findByStreamKey(sourceKey);
            if (session != null) {
                accepted = session.requestVideoKeyFrame(trackId) || accepted;
            }
        }
        return accepted;
    }

    @Override
    public void setPlaybackActive(StreamKey sourceKey, boolean active) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return;
        }
        TranscodeWorker videoWorker = ensureVideoWorker(sourceKey);
        if (videoWorker != null) {
            videoWorker.setPlaybackActive(active);
        }
        AudioTranscodeWorker audioWorker = ensureAudioWorker(sourceKey);
        if (audioWorker != null) {
            audioWorker.setPlaybackActive(active);
        }
    }

    private TranscodeWorker ensureVideoWorker(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return null;
        }
        TranscodeWorker worker = videoWorkersBySourceKey.get(sourceKey);
        if (worker != null) {
            return worker;
        }
        StreamKey derivedKey = derivedKey(sourceKey);
        VideoFrameCanonicalizer canonicalizer = buildCanonicalizer(sourceKey);
        if (canonicalizer == null) {
            return null;
        }
        VideoFrameTranscoderFactory transcoderFactory = new VideoFrameTranscoderFactory() {
            @Override
            public VideoFrameTranscoder create() {
                return new H264Avcc420pTranscoder();
            }
        };
        TranscodeWorker created = TranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                canonicalizer,
                transcoderFactory,
                new WebRtcH264ProfileDecisionPolicy(),
                videoQueueSize
        );
        TranscodeWorker existing = videoWorkersBySourceKey.putIfAbsent(sourceKey, created);
        if (existing != null) {
            created.stop();
            return existing;
        }
        log.debug("Created stream transform worker source={} derived={}", sourceKey, derivedKey);
        return created;
    }

    private AudioTranscodeWorker ensureAudioWorker(StreamKey sourceKey) {
        if (sourceKey == null || isDerivedStream(sourceKey)) {
            return null;
        }
        AudioTranscodeWorker worker = audioWorkersBySourceKey.get(sourceKey);
        if (worker != null) {
            return worker;
        }
        StreamKey derivedKey = derivedKey(sourceKey);
        AudioFrameCanonicalizer canonicalizer = buildAudioCanonicalizer(sourceKey);
        if (canonicalizer == null) {
            return null;
        }
        AudioFrameTranscoderFactory transcoderFactory = new AudioFrameTranscoderFactory() {
            @Override
            public AudioFrameTranscoder create() {
                return new AudioToG711UTranscoder();
            }
        };
        AudioTranscodeWorker created = AudioTranscodeWorker.start(
                sourceKey,
                derivedKey,
                publisher,
                canonicalizer,
                transcoderFactory,
                new WebRtcAudioCodecDecisionPolicy(),
                audioQueueSize
        );
        AudioTranscodeWorker existing = audioWorkersBySourceKey.putIfAbsent(sourceKey, created);
        if (existing != null) {
            created.stop();
            return existing;
        }
        log.debug("Created audio transform worker source={} derived={}", sourceKey, derivedKey);
        return created;
    }

    private VideoFrameCanonicalizer buildCanonicalizer(StreamKey sourceKey) {
        StreamProtocol protocol = sourceKey == null ? StreamProtocol.UNKNOWN : sourceKey.protocol();
        if (protocol == StreamProtocol.RTMP) {
            return new RtmpAvccH264Canonicalizer();
        }
        if (protocol == StreamProtocol.RTSP || protocol == StreamProtocol.WEBRTC) {
            return new RtspRtpH264Canonicalizer();
        }
        log.debug("No video frame canonicalizer registered yet for protocol={} source={}", protocol, sourceKey);
        return null;
    }

    private AudioFrameCanonicalizer buildAudioCanonicalizer(StreamKey sourceKey) {
        StreamProtocol protocol = sourceKey == null ? StreamProtocol.UNKNOWN : sourceKey.protocol();
        if (protocol == StreamProtocol.RTMP) {
            return new RtmpAudioCanonicalizer();
        }
        if (protocol == StreamProtocol.RTSP) {
            return new RtspRtpAudioCanonicalizer();
        }
        log.debug("No audio frame canonicalizer registered yet for protocol={} source={}", protocol, sourceKey);
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

    private StreamKey derivedKey(StreamKey sourceKey) {
        return new StreamKey(sourceKey.protocol(), sourceKey.app(), sourceKey.stream() + playbackSuffix);
    }

    private boolean isSupportedAudioFrame(InboundMediaFrame frame) {
        if (frame == null || frame.trackType() != TrackType.AUDIO) {
            return false;
        }
        return frame.codecType() == CodecType.AAC
                || frame.codecType() == CodecType.MPEG4_GENERIC
                || frame.codecType() == CodecType.G711A
                || frame.codecType() == CodecType.G711U;
    }

    private long resolveLatestTrackSsrc(StreamKey sourceKey, String trackId) {
        if (registry == null || sourceKey == null) {
            return 0L;
        }
        IPublishedStream stream = registry.findPublishedStream(sourceKey);
        Long ssrc = stream == null ? null : stream.latestTrackSsrc(trackId);
        return ssrc == null ? 0L : (ssrc.longValue() & 0xFFFFFFFFL);
    }
}
