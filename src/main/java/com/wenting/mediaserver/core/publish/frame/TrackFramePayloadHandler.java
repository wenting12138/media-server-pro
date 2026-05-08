package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public interface TrackFramePayloadHandler {

    void onFrame(InboundMediaFrame frame, TrackFramePayloadContext context);
}
