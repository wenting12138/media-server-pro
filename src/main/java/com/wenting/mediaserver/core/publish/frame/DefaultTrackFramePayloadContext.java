package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.PublishedTrackContext;

public final class DefaultTrackFramePayloadContext implements TrackFramePayloadContext {

    private final PublishedTrackContext trackContext;
    private final boolean hasAnyVideoFrame;

    public DefaultTrackFramePayloadContext(PublishedTrackContext trackContext, boolean hasAnyVideoFrame) {
        this.trackContext = trackContext;
        this.hasAnyVideoFrame = hasAnyVideoFrame;
    }

    @Override
    public PublishedTrackContext trackContext() {
        return trackContext;
    }

    @Override
    public boolean hasAnyVideoFrame() {
        return hasAnyVideoFrame;
    }

    @Override
    public void cacheConfigFrame(InboundMediaFrame frame) {
        if (trackContext != null && frame != null) {
            trackContext.latestConfigFrame(frame);
        }
    }

    @Override
    public void cacheKeyFrame(InboundMediaFrame frame) {
        if (trackContext != null && frame != null) {
            trackContext.latestKeyFrame(frame);
        }
    }
}
