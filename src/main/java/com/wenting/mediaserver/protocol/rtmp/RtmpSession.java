package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.model.StreamKey;

import java.util.UUID;

/**
 * Per-connection RTMP session state.
 */
public final class RtmpSession {

    private final String sessionId;
    private volatile long createdAtMillis;
    private volatile long lastActivityAtMillis;
    private volatile long inboundBytes;
    private volatile String app;
    private volatile String streamName;
    private volatile Integer messageStreamId;
    private volatile StreamKey streamKey;
    private volatile RtmpSessionRole role = RtmpSessionRole.UNKNOWN;

    public RtmpSession() {
        this(UUID.randomUUID().toString().replace("-", ""));
    }

    public RtmpSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        this.createdAtMillis = System.currentTimeMillis();
        this.lastActivityAtMillis = this.createdAtMillis;
    }

    public String sessionId() {
        return sessionId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long lastActivityAtMillis() {
        return lastActivityAtMillis;
    }

    public long inboundBytes() {
        return inboundBytes;
    }

    public void recordInboundBytes(int size) {
        if (size <= 0) {
            touch();
            return;
        }
        inboundBytes += size;
        touch();
    }

    public void touch() {
        lastActivityAtMillis = System.currentTimeMillis();
    }

    public String app() {
        return app;
    }

    public void app(String app) {
        this.app = normalize(app);
    }

    public String streamName() {
        return streamName;
    }

    public void streamName(String streamName) {
        this.streamName = normalize(streamName);
    }

    public Integer messageStreamId() {
        return messageStreamId;
    }

    public void messageStreamId(Integer messageStreamId) {
        this.messageStreamId = messageStreamId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public void streamKey(StreamKey streamKey) {
        this.streamKey = streamKey;
    }

    public boolean publishingReady() {
        return streamKey != null && streamName != null && !streamName.isEmpty();
    }

    public RtmpSessionRole role() {
        return role;
    }

    public void role(RtmpSessionRole role) {
        this.role = role == null ? RtmpSessionRole.UNKNOWN : role;
    }

    public boolean isPublisher() {
        return role == RtmpSessionRole.PUBLISHER;
    }

    public boolean isSubscriber() {
        return role == RtmpSessionRole.SUBSCRIBER;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
