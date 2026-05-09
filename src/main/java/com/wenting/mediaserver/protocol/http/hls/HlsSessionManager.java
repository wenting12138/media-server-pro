package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HlsSessionManager implements AutoCloseable {

    private static final int DEFAULT_PLAYLIST_SIZE = 6;
    private static final long DEFAULT_TARGET_DURATION_MILLIS = 2000L;

    private final StreamRegistry streamRegistry;
    private final Map<StreamKey, HlsSession> sessionsByStreamKey = new ConcurrentHashMap<StreamKey, HlsSession>();

    public HlsSessionManager(StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    public HlsSession ensureSession(StreamKey streamKey, IPublishedStream stream) {
        if (streamKey == null || stream == null) {
            throw new IllegalArgumentException("streamKey and stream must not be null");
        }
        HlsSession existing = sessionsByStreamKey.get(streamKey);
        if (existing != null) {
            return existing;
        }
        HlsSession created = new HlsSession(
                "hls-" + streamKey.app() + "-" + streamKey.stream(),
                streamKey,
                DEFAULT_PLAYLIST_SIZE,
                DEFAULT_TARGET_DURATION_MILLIS
        );
        attachRtspTracksIfPresent(created, streamKey);
        HlsSession previous = sessionsByStreamKey.putIfAbsent(streamKey, created);
        HlsSession target = previous == null ? created : previous;
        if (previous == null) {
            stream.addSubscriber(created);
        }
        return target;
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
        sessionsByStreamKey.clear();
    }
}
