package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HlsSessionManager implements AutoCloseable {

    private static final int DEFAULT_PLAYLIST_SIZE = 6;
    private static final long DEFAULT_TARGET_DURATION_MILLIS = 2000L;

    private final StreamRegistry streamRegistry;
    private final HlsStorageFactory storageFactory;
    private final Map<StreamKey, HlsSession> sessionsByStreamKey = new ConcurrentHashMap<StreamKey, HlsSession>();

    public HlsSessionManager(StreamRegistry streamRegistry) {
        this(streamRegistry, new InMemoryHlsStorageFactory());
    }

    public HlsSessionManager(StreamRegistry streamRegistry, HlsStorageFactory storageFactory) {
        this.streamRegistry = streamRegistry;
        this.storageFactory = storageFactory;
    }

    public HlsSessionManager(StreamRegistry streamRegistry, Path rootDirectory) {
        this(streamRegistry, new FileHlsStorageFactory(rootDirectory));
    }

    public HlsSession ensureSession(StreamKey streamKey, IPublishedStream stream) {
        if (streamKey == null || stream == null) {
            throw new IllegalArgumentException("streamKey and stream must not be null");
        }
        HlsSession existing = sessionsByStreamKey.get(streamKey);
        if (existing != null) {
            attachDerivedAudioIfPresent(existing, streamKey, stream);
            return existing;
        }
        HlsSession created = new HlsSession(
                "hls-" + streamKey.app() + "-" + streamKey.stream(),
                streamKey,
                DEFAULT_PLAYLIST_SIZE,
                DEFAULT_TARGET_DURATION_MILLIS,
                storageFactory.create(streamKey, DEFAULT_PLAYLIST_SIZE)
        );
        attachRtspTracksIfPresent(created, streamKey);
        HlsSession previous = sessionsByStreamKey.putIfAbsent(streamKey, created);
        HlsSession target = previous == null ? created : previous;
        if (previous == null) {
            stream.addSubscriber(created);
        }
        attachDerivedAudioIfPresent(target, streamKey, stream);
        return target;
    }

    private void attachDerivedAudioIfPresent(HlsSession session, StreamKey streamKey, IPublishedStream sourceStream) {
        if (session == null || streamKey == null || sourceStream == null || streamRegistry == null) {
            return;
        }
        if (session.derivedAudioAttached()) {
            return;
        }
        if (sourceStream.getProtocol() != StreamProtocol.WEBRTC) {
            return;
        }
        IPublishedStream derivedAudioStream =
                streamRegistry.findPublishedStreamForHlsAudioPlayback(streamKey.app(), streamKey.stream());
        if (derivedAudioStream == null || derivedAudioStream == sourceStream) {
            return;
        }
        derivedAudioStream.addSubscriber(session);
        session.markDerivedAudioAttached();
    }

    private void attachRtspTracksIfPresent(HlsSession session, StreamKey streamKey) {
        RtspSessionManager sessionManager = streamRegistry.getRtspSessionManager();
        if (sessionManager == null) {
            return;
        }
        RtspSession publisherSession = sessionManager.findByStreamKey(streamKey);
        if (publisherSession == null || publisherSession.trackList() == null) {
            return;
        }
        for (ITrack track : publisherSession.trackList()) {
            session.track(track);
        }
    }

    @Override
    public void close() {
        for (HlsSession session : sessionsByStreamKey.values()) {
            session.close();
        }
        sessionsByStreamKey.clear();
    }
}
