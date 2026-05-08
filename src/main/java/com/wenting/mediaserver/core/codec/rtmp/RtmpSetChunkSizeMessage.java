package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RtmpSetChunkSizeMessage extends RtmpMessage {

    private final int chunkSize;

    public RtmpSetChunkSizeMessage(int chunkSize) {
        this(2, 0L, 0, chunkSize);
    }

    public RtmpSetChunkSizeMessage(int chunkStreamId, long timestamp, int messageStreamId, int chunkSize) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.SET_CHUNK_SIZE, payload(chunkSize));
        this.chunkSize = chunkSize;
    }

    public int chunkSize() {
        return chunkSize;
    }

    private static byte[] payload(int chunkSize) {
        ByteBuf buffer = Unpooled.buffer(4);
        buffer.writeInt(chunkSize);
        byte[] bytes = new byte[4];
        buffer.readBytes(bytes);
        return bytes;
    }
}
