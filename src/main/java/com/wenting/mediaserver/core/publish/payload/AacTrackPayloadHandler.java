package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

/**
 * AAC RTP payload handler: no video-style keyframe semantics, only follow an active video GOP.
 */
public final class AacTrackPayloadHandler implements TrackPayloadHandler {

    public static final AacTrackPayloadHandler INSTANCE = new AacTrackPayloadHandler();

    private AacTrackPayloadHandler() {
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
