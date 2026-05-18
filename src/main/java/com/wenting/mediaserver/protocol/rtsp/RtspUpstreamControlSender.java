package com.wenting.mediaserver.protocol.rtsp;

/**
 * Sends upstream RTSP publisher control packets such as RTCP PLI.
 */
public interface RtspUpstreamControlSender {

    boolean requestVideoKeyFrame(String trackId, long mediaSsrc);
}
