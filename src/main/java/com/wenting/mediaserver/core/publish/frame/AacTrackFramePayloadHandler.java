package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public final class AacTrackFramePayloadHandler implements TrackFramePayloadHandler {

    public static final AacTrackFramePayloadHandler INSTANCE = new AacTrackFramePayloadHandler();

    private AacTrackFramePayloadHandler() {
    }

    @Override
    public void onFrame(InboundMediaFrame frame, TrackFramePayloadContext context) {
        if (frame == null || context == null) {
            return;
        }
        if (frame.configFrame()) {
            context.cacheConfigFrame(frame);
        }
    }
}
