package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.model.StreamKey;

/**
 * One UDP local-port binding associated with a specific RTSP session and media track.
 */
public final class RtspUdpBinding {

    private final String sessionId;
    private final StreamKey streamKey;
    private final String trackId;
    private final boolean rtcp;

    public RtspUdpBinding(String sessionId, StreamKey streamKey, String trackId, boolean rtcp) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.trackId = trackId == null ? "" : trackId;
        this.rtcp = rtcp;
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String trackId() {
        return trackId;
    }

    public boolean rtcp() {
        return rtcp;
    }

    @Override
    public String toString() {
        return "RtspUdpBinding{"
                + "sessionId='" + sessionId + '\''
                + ", streamKey=" + streamKey
                + ", trackId='" + trackId + '\''
                + ", rtcp=" + rtcp
                + '}';
    }
}
