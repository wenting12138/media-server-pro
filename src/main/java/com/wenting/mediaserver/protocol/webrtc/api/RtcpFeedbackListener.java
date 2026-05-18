package com.wenting.mediaserver.protocol.webrtc.api;

import java.util.List;

/**
 * Receiver for RTCP feedback relevant to one outbound RTP sender.
 */
public interface RtcpFeedbackListener {

    void onPictureLossIndication(long mediaSsrc);

    void onGenericNack(long mediaSsrc, List<Integer> lostSequenceNumbers);
}
