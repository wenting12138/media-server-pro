package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;

public final class WebRtcSubscriberAdapter implements MediaSubscriberAdapter {

    private final WebRtcSubscriberSession subscriberSession;

    public WebRtcSubscriberAdapter(WebRtcSubscriberSession subscriberSession) {
        this.subscriberSession = subscriberSession;
    }

    @Override
    public String sessionId() {
        return subscriberSession == null ? null : subscriberSession.sessionId();
    }

    @Override
    public boolean acceptsTrack(String trackId) {
        return subscriberSession != null && subscriberSession.acceptsTrack(trackId);
    }

    @Override
    public void writeMediaPacket(InboundRtpPacket packet) {
        if (subscriberSession != null) {
            subscriberSession.writeMediaPacket(packet);
        }
    }

    @Override
    public void writeInboundFrame(InboundMediaFrame frame) {
        if (subscriberSession != null) {
            subscriberSession.writeInboundFrame(frame);
        }
    }
}
