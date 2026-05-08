package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public final class PassThroughTrackFramePayloadHandler implements TrackFramePayloadHandler {

    public static final PassThroughTrackFramePayloadHandler INSTANCE = new PassThroughTrackFramePayloadHandler();

    private PassThroughTrackFramePayloadHandler() {
    }

    @Override
    public void onFrame(InboundMediaFrame frame, TrackFramePayloadContext context) {
        if (frame == null || context == null) {
            return;
        }
        if (frame.configFrame()) {
            context.cacheConfigFrame(frame);
        }
        if (frame.keyFrame()) {
            context.cacheKeyFrame(frame);
        }
    }
}
