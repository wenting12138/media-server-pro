package com.wenting.mediaserver.core.transcode.publish;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultDerivedStreamPublisher implements DerivedStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultDerivedStreamPublisher.class);

    private final StreamRegistry registry;

    public DefaultDerivedStreamPublisher(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    public DefaultPublishedStream ensureStream(StreamKey derivedKey) {
        if (registry == null || derivedKey == null) {
            return null;
        }
        IPublishedStream existing = registry.findPublishedStream(derivedKey);
        if (existing instanceof DefaultPublishedStream) {
            return (DefaultPublishedStream) existing;
        }
        DefaultPublishedStream created = new DefaultPublishedStream(derivedKey);
        IPublishedStream previous = registry.registerPublishedStream(derivedKey, created);
        if (previous instanceof DefaultPublishedStream) {
            return (DefaultPublishedStream) previous;
        }
        if (previous == null) {
            return created;
        }
        log.warn("Derived stream already exists but type is not DefaultPublishedStream: {}", derivedKey);
        return null;
    }

    @Override
    public void publish(StreamKey derivedKey, InboundMediaFrame frame) {
        DefaultPublishedStream stream = ensureStream(derivedKey);
        if (stream != null && frame != null) {
            stream.onInboundFrame(frame);
        }
    }

    @Override
    public void removeStream(StreamKey derivedKey) {
        if (registry != null && derivedKey != null) {
            registry.removePublishedStream(derivedKey);
        }
    }
}
