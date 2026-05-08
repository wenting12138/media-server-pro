package com.wenting.mediaserver.core.publish;

/**
 * Protocol-neutral subscriber writer used by the published stream core.
 */
public interface MediaSubscriberAdapter {

    String sessionId();

    boolean acceptsTrack(String trackId);

    void writeMediaPacket(InboundRtpPacket packet);

    default void writeInboundFrame(InboundMediaFrame frame) {
    }
}
