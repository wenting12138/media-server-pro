package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RtmpAbortMessage extends RtmpMessage {

    private final int targetChunkStreamId;

    public RtmpAbortMessage(int chunkStreamId, long timestamp, int messageStreamId, int targetChunkStreamId) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.ABORT, payload(targetChunkStreamId));
        this.targetChunkStreamId = targetChunkStreamId;
    }

    public int targetChunkStreamId() {
        return targetChunkStreamId;
    }

    private static byte[] payload(int targetChunkStreamId) {
        ByteBuf buffer = Unpooled.buffer(4);
        buffer.writeInt(targetChunkStreamId);
        byte[] bytes = new byte[4];
        buffer.readBytes(bytes);
        return bytes;
    }
}
