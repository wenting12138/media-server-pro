package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;

/**
 * RTSP-specific subscriber writer adapter for the protocol-neutral publish core.
 */
public final class RtspSubscriberAdapter implements MediaSubscriberAdapter {

    private final RtspSubscriberSession subscriberSession;

    public RtspSubscriberAdapter(RtspSubscriberSession subscriberSession) {
        if (subscriberSession == null) {
            throw new IllegalArgumentException("subscriberSession must not be null");
        }
        this.subscriberSession = subscriberSession;
    }

    @Override
    public String sessionId() {
        return subscriberSession.sessionId();
    }

    @Override
    public boolean acceptsTrack(String trackId) {
        return subscriberSession.acceptsTrack(trackId);
    }

    @Override
    public void writeMediaPacket(InboundRtpPacket packet) {
        subscriberSession.writeMediaPacket(packet);
    }

    @Override
    public void writeInboundFrame(InboundMediaFrame frame) {
        subscriberSession.writeInboundFrame(frame);
    }

    public RtspSubscriberSession subscriberSession() {
        return subscriberSession;
    }
}
