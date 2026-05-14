package com.wenting.mediaserver.core.publish;

/**
 * Observer for non-RTCP RTP packets after transport-specific ordering/reordering.
 */
public interface OrderedRtpPacketObserver {

    void onOrderedRtpPacket(InboundRtpPacket packet);
}
