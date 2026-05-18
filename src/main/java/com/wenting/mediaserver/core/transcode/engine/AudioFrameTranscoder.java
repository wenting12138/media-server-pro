package com.wenting.mediaserver.core.transcode.engine;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.transcode.canonical.CanonicalAudioFrame;

import java.util.List;

public interface AudioFrameTranscoder extends AutoCloseable {

    List<InboundMediaFrame> transcode(CanonicalAudioFrame frame, StreamKey derivedKey);

    @Override
    void close();
}
