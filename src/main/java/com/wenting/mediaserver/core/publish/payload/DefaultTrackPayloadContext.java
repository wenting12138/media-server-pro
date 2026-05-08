package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.publish.PublishedTrackContext;

/**
 * Default handler context implementation backed by a published track context.
 */
public final class DefaultTrackPayloadContext implements TrackPayloadContext {

    private final PublishedTrackContext trackContext;
    private final boolean hasAnyVideoGop;

    public DefaultTrackPayloadContext(PublishedTrackContext trackContext, boolean hasAnyVideoGop) {
        this.trackContext = trackContext;
        this.hasAnyVideoGop = hasAnyVideoGop;
    }

    @Override
    public PublishedTrackContext trackContext() {
        return trackContext;
    }

    @Override
    public boolean hasAnyVideoGop() {
        return hasAnyVideoGop;
    }
}
