package com.wenting.mediaserver.core.transcode.orchestrator;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

/**
 * Owns derived-stream transform sessions across publish lifecycle events.
 */
public interface StreamTransformOrchestrator extends AutoCloseable {

    void onStreamRegistered(StreamKey sourceKey);

    void onStreamRemoved(StreamKey sourceKey);

    void onFrame(InboundMediaFrame frame);

    default void onPacket(InboundRtpPacket packet) {
    }

    default boolean requestKeyFrame(StreamKey sourceKey, String trackId) {
        return false;
    }

    default void setPlaybackActive(StreamKey sourceKey, boolean active) {
    }

    default void setPlaybackActive(StreamKey sourceKey, StreamKey derivedKey, boolean active) {
        setPlaybackActive(sourceKey, active);
    }

    default boolean managesDerivedStream(StreamKey derivedKey) {
        return false;
    }

    default StreamKey sourceKeyForDerived(StreamKey derivedKey) {
        return derivedKey;
    }

    default boolean shouldRequestKeyFrameOnFirstSubscriber(StreamKey derivedKey) {
        return false;
    }

    @Override
    void close();
}
