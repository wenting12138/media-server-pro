package com.wenting.mediaserver.core.codec.rtmp;

import java.util.Arrays;

public abstract class RtmpMessage {

    private final int chunkStreamId;
    private final long timestamp;
    private final int messageStreamId;
    private final int messageTypeId;
    private final byte[] payload;

    protected RtmpMessage(int chunkStreamId, long timestamp, int messageStreamId, int messageTypeId, byte[] payload) {
        this.chunkStreamId = chunkStreamId;
        this.timestamp = timestamp;
        this.messageStreamId = messageStreamId;
        this.messageTypeId = messageTypeId;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public int chunkStreamId() {
        return chunkStreamId;
    }

    public long timestamp() {
        return timestamp;
    }

    public int messageStreamId() {
        return messageStreamId;
    }

    public int messageTypeId() {
        return messageTypeId;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int payloadLength() {
        return payload.length;
    }
}
