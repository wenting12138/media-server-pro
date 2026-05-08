package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class RtmpSetPeerBandwidthMessage extends RtmpMessage {

    private final int windowSize;
    private final int limitType;

    public RtmpSetPeerBandwidthMessage(int windowSize, int limitType) {
        this(2, 0L, 0, windowSize, limitType);
    }

    public RtmpSetPeerBandwidthMessage(int chunkStreamId, long timestamp, int messageStreamId, int windowSize, int limitType) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.SET_PEER_BANDWIDTH, payload(windowSize, limitType));
        this.windowSize = windowSize;
        this.limitType = limitType;
    }

    public int windowSize() {
        return windowSize;
    }

    public int limitType() {
        return limitType;
    }

    private static byte[] payload(int windowSize, int limitType) {
        ByteBuf buffer = Unpooled.buffer(5);
        buffer.writeInt(windowSize);
        buffer.writeByte(limitType);
        byte[] bytes = new byte[5];
        buffer.readBytes(bytes);
        return bytes;
    }
}
