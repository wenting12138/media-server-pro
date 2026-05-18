package com.wenting.mediaserver.core.publish;

/**
 * Callback used by playback sessions to request a fresh key frame from the source pipeline.
 */
public interface KeyFrameRequestHandler {

    boolean requestKeyFrame(String trackId);
}
