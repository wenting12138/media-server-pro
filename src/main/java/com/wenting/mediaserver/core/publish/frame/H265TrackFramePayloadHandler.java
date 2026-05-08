package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public final class H265TrackFramePayloadHandler implements TrackFramePayloadHandler {

    public static final H265TrackFramePayloadHandler INSTANCE = new H265TrackFramePayloadHandler();

    private H265TrackFramePayloadHandler() {
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
