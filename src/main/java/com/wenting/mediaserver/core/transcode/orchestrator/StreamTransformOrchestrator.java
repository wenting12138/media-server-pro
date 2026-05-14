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

    @Override
    void close();
}
