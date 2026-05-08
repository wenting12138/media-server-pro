package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

/**
 * Minimal payload handler for tracks without codec-specific RTP processing.
 */
public final class PassThroughTrackPayloadHandler implements TrackPayloadHandler {

    public static final PassThroughTrackPayloadHandler INSTANCE = new PassThroughTrackPayloadHandler();

    private PassThroughTrackPayloadHandler() {
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
