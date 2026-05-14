package com.wenting.mediaserver.core.transcode.engine;

import com.wenting.mediaserver.core.transcode.canonical.CanonicalVideoFrame;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;

import java.util.List;

public interface VideoFrameTranscoder extends AutoCloseable {

    List<InboundMediaFrame> transcode(CanonicalVideoFrame frame, StreamKey derivedKey);

    @Override
    void close();
}
