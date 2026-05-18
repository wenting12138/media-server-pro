package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.protocol.rtmp.RtmpSessionManager;
import com.wenting.mediaserver.core.transcode.orchestrator.StreamTransformOrchestrator;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import io.netty.buffer.ByteBufUtil;
import com.wenting.mediaserver.protocol.rtsp.RtspUdpBinding;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory published streams index. One live publisher per {@link StreamKey}.
 */
public final class StreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);
    private static final String DEFAULT_WEBRTC_PLAYBACK_SUFFIX = "__webrtc";

    private final RtspSessionManager sessionManager;
    private final RtmpSessionManager rtmpSessionManager;
    private final Map<StreamKey, IPublishedStream> published = new ConcurrentHashMap<StreamKey, IPublishedStream>();
    private final Map<Integer, RtspUdpBinding> udpPortBindings = new ConcurrentHashMap<Integer, RtspUdpBinding>();
    private final String webRtcPlaybackSuffix;
    private volatile StreamTransformOrchestrator streamTransformOrchestrator;

    public StreamRegistry() {
        this(new RtspSessionManager(), new RtmpSessionManager());
    }

    public StreamRegistry(RtspSessionManager sessionManager) {
        this(sessionManager, new RtmpSessionManager(), DEFAULT_WEBRTC_PLAYBACK_SUFFIX);
    }

    public StreamRegistry(RtspSessionManager sessionManager, RtmpSessionManager rtmpSessionManager) {
        this(sessionManager, rtmpSessionManager, DEFAULT_WEBRTC_PLAYBACK_SUFFIX);
    }

    public StreamRegistry(RtspSessionManager sessionManager, String webRtcPlaybackSuffix) {
        this(sessionManager, new RtmpSessionManager(), webRtcPlaybackSuffix);
    }

    public StreamRegistry(RtspSessionManager sessionManager, RtmpSessionManager rtmpSessionManager, String webRtcPlaybackSuffix) {
        this.sessionManager = sessionManager == null ? new RtspSessionManager() : sessionManager;
        this.rtmpSessionManager = rtmpSessionManager == null ? new RtmpSessionManager() : rtmpSessionManager;
        this.webRtcPlaybackSuffix = webRtcPlaybackSuffix == null || webRtcPlaybackSuffix.trim().isEmpty()
                ? DEFAULT_WEBRTC_PLAYBACK_SUFFIX
                : webRtcPlaybackSuffix.trim();
    }

    public IPublishedStream registerPublishedStream(StreamKey key, IPublishedStream stream) {
        if (key == null || stream == null) {
            throw new IllegalArgumentException("key and stream must not be null");
        }
        attachStreamObservers(key, stream);
        IPublishedStream previous = published.put(key, stream);
        StreamTransformOrchestrator manager = streamTransformOrchestrator;
        if (manager != null) {
            manager.onStreamRegistered(key);
        }
        return previous;
    }

    public IPublishedStream findPublishedStream(StreamKey key) {
        return key == null ? null : published.get(key);
    }

    public IPublishedStream findPublishedStreamByPath(String app, String stream) {
        if (app == null || app.trim().isEmpty() || stream == null || stream.trim().isEmpty()) {
            return null;
        }
        for (Map.Entry<StreamKey, IPublishedStream> entry : published.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (app.equals(entry.getKey().app()) && stream.equals(entry.getKey().stream())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public IPublishedStream findPublishedStreamForWebRtcPlayback(String app, String stream) {
        if (app == null || app.trim().isEmpty() || stream == null || stream.trim().isEmpty()) {
            return null;
        }
        IPublishedStream derived = findPublishedStreamByPath(app, stream + webRtcPlaybackSuffix);
        return derived == null ? findPublishedStreamByPath(app, stream) : derived;
    }

    public Map<StreamKey, IPublishedStream> publishedStreamsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<StreamKey, IPublishedStream>(published));
    }

    public IPublishedStream removePublishedStream(StreamKey key) {
        if (key == null) {
            return null;
        }
        IPublishedStream removed = published.remove(key);
        StreamTransformOrchestrator manager = streamTransformOrchestrator;
        if (manager != null) {
            manager.onStreamRemoved(key);
        }
        return removed;
    }

    public void onPublishedFrame(InboundMediaFrame frame) {
        StreamTransformOrchestrator manager = streamTransformOrchestrator;
        if (manager != null) {
            manager.onFrame(frame);
        }
    }

    public void onPublishedPacket(InboundRtpPacket packet) {
        StreamTransformOrchestrator manager = streamTransformOrchestrator;
        if (manager != null) {
            manager.onPacket(packet);
        }
    }

    public void setStreamTransformOrchestrator(StreamTransformOrchestrator streamTransformOrchestrator) {
        this.streamTransformOrchestrator = streamTransformOrchestrator;
        for (Map.Entry<StreamKey, IPublishedStream> entry : published.entrySet()) {
            attachStreamObservers(entry.getKey(), entry.getValue());
        }
    }

    public String webRtcPlaybackSuffix() {
        return webRtcPlaybackSuffix;
    }

    public void bindUdpPort(int localPort, RtspUdpBinding binding) {
        if (localPort <= 0 || binding == null) {
            throw new IllegalArgumentException("localPort must be positive and binding must not be null");
        }
        udpPortBindings.put(localPort, binding);
    }

    public void unbindUdpPort(int localPort) {
        if (localPort > 0) {
            udpPortBindings.remove(localPort);
        }
    }

    public Optional<RtspUdpBinding> findUdpBindingByPort(int localPort) {
        return Optional.ofNullable(udpPortBindings.get(localPort));
    }

    public void onUdpPacket(int localPort, InetSocketAddress remoteAddress, ByteBuf payload) {
        RtspUdpBinding binding = udpPortBindings.get(localPort);
        if (binding == null) {
            if (log.isDebugEnabled()) {
                log.debug("Dropping UDP packet on unbound local port {} from {}", localPort, remoteAddress);
            }
            return;
        }
        IPublishedStream stream = published.get(binding.streamKey());
        if (stream == null) {
            if (log.isDebugEnabled()) {
                log.debug("Dropping UDP packet for missing stream {} on local port {}", binding.streamKey(), localPort);
            }
            return;
        }
        ITrack track = resolveTrack(binding.sessionId(), binding.trackId());
        InboundRtpPacket packet = new InboundRtpPacket(
                new InboundMediaFrame(
                        stream.getProtocol(),
                        track == null ? TrackType.UNKNOWN : track.trackType(),
                        track == null ? CodecType.UNKNOWN : track.codecType(),
                        binding.sessionId(),
                        binding.streamKey(),
                        binding.trackId(),
                        null,
                        null,
                        false,
                        false,
                        track != null && track.outOfBandParameterSetsReady(),
                        remoteAddress,
                        ByteBufUtil.getBytes(payload, payload.readerIndex(), payload.readableBytes(), false)
                ),
                track == null ? 0 : track.clockRate(),
                binding.rtcp(),
                MediaPacketTransport.UDP,
                Integer.valueOf(localPort),
                null
        );
        stream.onInboundRtpPacket(packet);
    }

    private void attachStreamObservers(StreamKey key, IPublishedStream stream) {
        if (!(stream instanceof DefaultPublishedStream)) {
            return;
        }
        StreamTransformOrchestrator manager = streamTransformOrchestrator;
        DefaultPublishedStream publishedStream = (DefaultPublishedStream) stream;
        publishedStream.setOrderedRtpPacketObserver(
                manager == null ? null : manager::onPacket
        );
        publishedStream.setKeyFrameRequestHandler(
                manager == null || !isDerivedPlaybackStream(key)
                        ? null
                        : new com.wenting.mediaserver.core.publish.KeyFrameRequestHandler() {
                            @Override
                            public boolean requestKeyFrame(String trackId) {
                                return manager.requestKeyFrame(sourceKeyForDerived(key), trackId);
                            }
                        }
        );
    }

    private boolean isDerivedPlaybackStream(StreamKey key) {
        return key != null && key.stream() != null && key.stream().endsWith(webRtcPlaybackSuffix);
    }

    private StreamKey sourceKeyForDerived(StreamKey derivedKey) {
        if (!isDerivedPlaybackStream(derivedKey)) {
            return derivedKey;
        }
        String sourceStream = derivedKey.stream().substring(0, derivedKey.stream().length() - webRtcPlaybackSuffix.length());
        return new StreamKey(derivedKey.protocol(), derivedKey.app(), sourceStream);
    }

    private ITrack resolveTrack(String sessionId, String trackId) {
        if (sessionManager == null) {
            return null;
        }
        RtspSession session = sessionManager.find(sessionId);
        return session == null ? null : session.findTrack(trackId);
    }

    public RtspSessionManager getRtspSessionManager() {
        return sessionManager;
    }

    public RtmpSessionManager getRtmpSessionManager() {
        return rtmpSessionManager;
    }
}
