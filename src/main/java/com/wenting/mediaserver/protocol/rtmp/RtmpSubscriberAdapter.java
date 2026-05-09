package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;

/**
 * RTMP protocol adapter for published stream subscribers.
 */
public final class RtmpSubscriberAdapter implements MediaSubscriberAdapter {

    private final RtmpSubscriberSession session;

    public RtmpSubscriberAdapter(RtmpSubscriberSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        this.session = session;
    }

    @Override
    public String sessionId() {
        return session.sessionId();
    }

    @Override
    public boolean acceptsTrack(String trackId) {
        return true;
    }

    @Override
    public void writeMediaPacket(InboundRtpPacket packet) {
        session.writeMediaPacket(packet);
    }

    @Override
    public void writeInboundFrame(InboundMediaFrame frame) {
        session.writeInboundFrame(frame);
    }
}
