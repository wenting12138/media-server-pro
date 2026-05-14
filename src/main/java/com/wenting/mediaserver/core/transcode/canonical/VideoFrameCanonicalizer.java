package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;

import java.util.Collections;
import java.util.List;

public interface VideoFrameCanonicalizer extends AutoCloseable {

    CanonicalVideoFrame canonicalize(InboundMediaFrame frame);

    default List<CanonicalVideoFrame> canonicalize(InboundRtpPacket packet, ITrack track) {
        return Collections.emptyList();
    }

    @Override
    void close();
}
