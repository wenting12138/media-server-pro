package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RtmpAcknowledgementMessage extends RtmpMessage {

    private final int sequenceNumber;

    public RtmpAcknowledgementMessage(int sequenceNumber) {
        this(2, 0L, 0, sequenceNumber);
    }

    public RtmpAcknowledgementMessage(int chunkStreamId, long timestamp, int messageStreamId, int sequenceNumber) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.ACKNOWLEDGEMENT, payload(sequenceNumber));
        this.sequenceNumber = sequenceNumber;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    private static byte[] payload(int sequenceNumber) {
        ByteBuf buffer = Unpooled.buffer(4);
        buffer.writeInt(sequenceNumber);
        byte[] bytes = new byte[4];
        buffer.readBytes(bytes);
        return bytes;
    }
}
