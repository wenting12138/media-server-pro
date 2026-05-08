package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpChunkHeader {

    private final int format;
    private final int chunkStreamId;
    private final long timestamp;
    private final long timestampDelta;
    private final int messageLength;
    private final int messageTypeId;
    private final int messageStreamId;
    private final boolean extendedTimestamp;
    private final boolean continuation;

    public RtmpChunkHeader(
            int format,
            int chunkStreamId,
            long timestamp,
            long timestampDelta,
            int messageLength,
            int messageTypeId,
            int messageStreamId,
            boolean extendedTimestamp,
            boolean continuation
    ) {
        this.format = format;
        this.chunkStreamId = chunkStreamId;
        this.timestamp = timestamp;
        this.timestampDelta = timestampDelta;
        this.messageLength = messageLength;
        this.messageTypeId = messageTypeId;
        this.messageStreamId = messageStreamId;
        this.extendedTimestamp = extendedTimestamp;
        this.continuation = continuation;
    }

    public int format() {
        return format;
    }

    public int chunkStreamId() {
        return chunkStreamId;
    }

    public long timestamp() {
        return timestamp;
    }

    public long timestampDelta() {
        return timestampDelta;
    }

    public int messageLength() {
        return messageLength;
    }

    public int messageTypeId() {
        return messageTypeId;
    }

    public int messageStreamId() {
        return messageStreamId;
    }

    public boolean extendedTimestamp() {
        return extendedTimestamp;
    }

    public boolean continuation() {
        return continuation;
    }
}
