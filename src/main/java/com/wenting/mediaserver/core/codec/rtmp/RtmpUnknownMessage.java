package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpUnknownMessage extends RtmpMessage {

    public RtmpUnknownMessage(int chunkStreamId, long timestamp, int messageStreamId, int messageTypeId, byte[] payload) {
        super(chunkStreamId, timestamp, messageStreamId, messageTypeId, payload);
    }
}
