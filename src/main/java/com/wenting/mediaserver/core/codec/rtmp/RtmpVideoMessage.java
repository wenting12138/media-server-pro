package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpVideoMessage extends RtmpMessage {

    private final int frameType;
    private final int codecId;
    private final Integer avcPacketType;
    private final Integer compositionTime;

    public RtmpVideoMessage(int chunkStreamId, long timestamp, int messageStreamId, byte[] payload) {
        this(chunkStreamId, timestamp, messageStreamId, payload, parseFrameType(payload), parseCodecId(payload), parseAvcPacketType(payload), parseCompositionTime(payload));
    }

    public RtmpVideoMessage(
            int chunkStreamId,
            long timestamp,
            int messageStreamId,
            byte[] payload,
            int frameType,
            int codecId,
            Integer avcPacketType,
            Integer compositionTime
    ) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.VIDEO, payload);
        this.frameType = frameType;
        this.codecId = codecId;
        this.avcPacketType = avcPacketType;
        this.compositionTime = compositionTime;
    }

    public int frameType() {
        return frameType;
    }

    public int codecId() {
        return codecId;
    }

    public Integer avcPacketType() {
        return avcPacketType;
    }

    public Integer compositionTime() {
        return compositionTime;
    }

    private static int parseFrameType(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : ((payload[0] & 0xFF) >> 4) & 0x0F;
    }

    private static int parseCodecId(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : (payload[0] & 0x0F);
    }

    private static Integer parseAvcPacketType(byte[] payload) {
        return payload != null && payload.length > 1 ? Integer.valueOf(payload[1] & 0xFF) : null;
    }

    private static Integer parseCompositionTime(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return null;
        }
        int value = ((payload[2] & 0xFF) << 16) | ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return Integer.valueOf(value);
    }
}
