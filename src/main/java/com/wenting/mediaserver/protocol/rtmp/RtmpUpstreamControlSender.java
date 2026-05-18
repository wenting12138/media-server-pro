package com.wenting.mediaserver.protocol.rtmp;

/**
 * Sends upstream RTMP publisher control messages such as best-effort keyframe requests.
 */
public interface RtmpUpstreamControlSender {

    boolean requestVideoKeyFrame(String trackId);
}
