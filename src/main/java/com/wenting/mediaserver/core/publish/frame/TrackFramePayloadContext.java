package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.PublishedTrackContext;

public interface TrackFramePayloadContext {

    PublishedTrackContext trackContext();

    boolean hasAnyVideoFrame();

    void cacheConfigFrame(InboundMediaFrame frame);

    void cacheKeyFrame(InboundMediaFrame frame);
}
