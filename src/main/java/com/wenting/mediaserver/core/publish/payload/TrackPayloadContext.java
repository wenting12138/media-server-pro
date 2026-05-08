package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.publish.PublishedTrackContext;

/**
 * Narrow context exposed to codec-specific payload handlers.
 */
public interface TrackPayloadContext {

    PublishedTrackContext trackContext();

    boolean hasAnyVideoGop();
}
