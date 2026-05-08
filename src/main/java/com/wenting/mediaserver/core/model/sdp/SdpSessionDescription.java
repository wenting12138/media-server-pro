package com.wenting.mediaserver.core.model.sdp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed SDP session description.
 */
public final class SdpSessionDescription {

    private final String version;
    private final String origin;
    private final String sessionName;
    private final String connection;
    private final String timing;
    private final Map<String, String> attributes;
    private final List<SdpMediaDescription> mediaDescriptions;

    public SdpSessionDescription(
            String version,
            String origin,
            String sessionName,
            String connection,
            String timing,
            Map<String, String> attributes,
            List<SdpMediaDescription> mediaDescriptions
    ) {
        this.version = version;
        this.origin = origin;
        this.sessionName = sessionName;
        this.connection = connection;
        this.timing = timing;
        this.attributes = attributes == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(attributes);
        this.mediaDescriptions = mediaDescriptions == null ? Collections.<SdpMediaDescription>emptyList() : Collections.unmodifiableList(mediaDescriptions);
    }

    public String version() {
        return version;
    }

    public String origin() {
        return origin;
    }

    public String sessionName() {
        return sessionName;
    }

    public String connection() {
        return connection;
    }

    public String timing() {
        return timing;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public List<SdpMediaDescription> mediaDescriptions() {
        return mediaDescriptions;
    }

    public SdpMediaDescription firstMedia(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        for (SdpMediaDescription media : mediaDescriptions) {
            if (mediaType.equalsIgnoreCase(media.mediaType())) {
                return media;
            }
        }
        return null;
    }
}
