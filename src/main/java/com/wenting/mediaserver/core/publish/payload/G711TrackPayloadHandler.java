package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

/**
 * G711 RTP payload handler: no config or keyframe semantics, only follow an active video GOP.
 */
public final class G711TrackPayloadHandler implements TrackPayloadHandler {

    public static final G711TrackPayloadHandler INSTANCE = new G711TrackPayloadHandler();

    private G711TrackPayloadHandler() {
    }

    @Override
    public void onRtpPacket(InboundRtpPacket packet, RtpPacketHeader header, TrackPayloadContext context) {
        if (packet == null || header == null || context == null || context.trackContext() == null) {
            return;
        }
        if (context.hasAnyVideoGop()) {
            context.trackContext().gopCache().append(packet, Long.valueOf(header.timestamp()));
        }
    }
}
