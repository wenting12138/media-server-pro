package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RtmpWindowAcknowledgementSizeMessage extends RtmpMessage {

    private final int windowSize;

    public RtmpWindowAcknowledgementSizeMessage(int windowSize) {
        this(2, 0L, 0, windowSize);
    }

    public RtmpWindowAcknowledgementSizeMessage(int chunkStreamId, long timestamp, int messageStreamId, int windowSize) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.WINDOW_ACKNOWLEDGEMENT_SIZE, payload(windowSize));
        this.windowSize = windowSize;
    }

    public int windowSize() {
        return windowSize;
    }

    private static byte[] payload(int windowSize) {
        ByteBuf buffer = Unpooled.buffer(4);
        buffer.writeInt(windowSize);
        byte[] bytes = new byte[4];
        buffer.readBytes(bytes);
        return bytes;
    }
}
