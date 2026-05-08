package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpChunkStreamState {

    private final int chunkStreamId;
    private long lastTimestamp;
    private long lastTimestampDelta;
    private int lastMessageLength;
    private int lastMessageTypeId;
    private int lastMessageStreamId;
    private boolean lastExtendedTimestamp;
    private RtmpChunkMessage currentMessage;

    public RtmpChunkStreamState(int chunkStreamId) {
        this.chunkStreamId = chunkStreamId;
    }

    public int chunkStreamId() {
        return chunkStreamId;
    }

    public long lastTimestamp() {
        return lastTimestamp;
    }

    public void lastTimestamp(long lastTimestamp) {
        this.lastTimestamp = lastTimestamp;
    }

    public long lastTimestampDelta() {
        return lastTimestampDelta;
    }

    public void lastTimestampDelta(long lastTimestampDelta) {
        this.lastTimestampDelta = lastTimestampDelta;
    }

    public int lastMessageLength() {
        return lastMessageLength;
    }

    public void lastMessageLength(int lastMessageLength) {
        this.lastMessageLength = lastMessageLength;
    }

    public int lastMessageTypeId() {
        return lastMessageTypeId;
    }

    public void lastMessageTypeId(int lastMessageTypeId) {
        this.lastMessageTypeId = lastMessageTypeId;
    }

    public int lastMessageStreamId() {
        return lastMessageStreamId;
    }

    public void lastMessageStreamId(int lastMessageStreamId) {
        this.lastMessageStreamId = lastMessageStreamId;
    }

    public boolean lastExtendedTimestamp() {
        return lastExtendedTimestamp;
    }

    public void lastExtendedTimestamp(boolean lastExtendedTimestamp) {
        this.lastExtendedTimestamp = lastExtendedTimestamp;
    }

    public RtmpChunkMessage currentMessage() {
        return currentMessage;
    }

    public void currentMessage(RtmpChunkMessage currentMessage) {
        this.currentMessage = currentMessage;
    }
}
