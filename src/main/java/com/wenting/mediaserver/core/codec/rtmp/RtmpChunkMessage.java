package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;

public final class RtmpChunkMessage {

    private final int chunkStreamId;
    private final long timestamp;
    private final int messageStreamId;
    private final int messageTypeId;
    private final byte[] payload;
    private int writeOffset;

    public RtmpChunkMessage(int chunkStreamId, long timestamp, int messageStreamId, int messageTypeId, int messageLength) {
        this.chunkStreamId = chunkStreamId;
        this.timestamp = timestamp;
        this.messageStreamId = messageStreamId;
        this.messageTypeId = messageTypeId;
        this.payload = new byte[Math.max(0, messageLength)];
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

    public int remaining() {
        return payload.length - writeOffset;
    }

    public void append(ByteBuf buffer, int length) {
        if (length <= 0) {
            return;
        }
        buffer.readBytes(payload, writeOffset, length);
        writeOffset += length;
    }

    public boolean complete() {
        return writeOffset >= payload.length;
    }

    public byte[] payload() {
        return payload;
    }
}
