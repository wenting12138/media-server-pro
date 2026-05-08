package com.wenting.mediaserver.core.publish.frame;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public final class G711TrackFramePayloadHandler implements TrackFramePayloadHandler {

    public static final G711TrackFramePayloadHandler INSTANCE = new G711TrackFramePayloadHandler();

    private G711TrackFramePayloadHandler() {
    }

    @Override
    public void onFrame(InboundMediaFrame frame, TrackFramePayloadContext context) {
    }
}
