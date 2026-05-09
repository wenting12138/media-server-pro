package com.wenting.mediaserver.protocol.http.flv;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;

public final class HttpFlvSubscriberAdapter implements MediaSubscriberAdapter {

    private final HttpFlvSubscriberSession session;

    public HttpFlvSubscriberAdapter(HttpFlvSubscriberSession session) {
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
