package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;

/**
 * Codec-specific RTP payload handler for one published track.
 */
public interface TrackPayloadHandler {

    void onRtpPacket(InboundRtpPacket packet, RtpPacketHeader header, TrackPayloadContext context);

    default void onRtcpPacket(InboundRtpPacket packet, TrackPayloadContext context) {
    }
}
