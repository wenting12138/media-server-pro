package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
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

    private final RtspSessionManager sessionManager;
    private final Map<StreamKey, IPublishedStream> published = new ConcurrentHashMap<StreamKey, IPublishedStream>();
    private final Map<Integer, RtspUdpBinding> udpPortBindings = new ConcurrentHashMap<Integer, RtspUdpBinding>();

    public StreamRegistry() {
        this(new RtspSessionManager());
    }

    public StreamRegistry(RtspSessionManager sessionManager) {
        this.sessionManager = sessionManager == null ? new RtspSessionManager() : sessionManager;
    }

    public IPublishedStream registerPublishedStream(StreamKey key, IPublishedStream stream) {
        if (key == null || stream == null) {
            throw new IllegalArgumentException("key and stream must not be null");
        }
        return published.put(key, stream);
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

    public Map<StreamKey, IPublishedStream> publishedStreamsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<StreamKey, IPublishedStream>(published));
    }

    public IPublishedStream removePublishedStream(StreamKey key) {
        if (key == null) {
            return null;
        }
        return published.remove(key);
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
        stream.onInboundRtpPacket(new InboundRtpPacket(
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
        ));
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
}
