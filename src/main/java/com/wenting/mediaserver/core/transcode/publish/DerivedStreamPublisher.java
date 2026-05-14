package com.wenting.mediaserver.core.transcode.publish;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;

public interface DerivedStreamPublisher {

    DefaultPublishedStream ensureStream(StreamKey derivedKey);

    void publish(StreamKey derivedKey, InboundMediaFrame frame);

    void removeStream(StreamKey derivedKey);
}
