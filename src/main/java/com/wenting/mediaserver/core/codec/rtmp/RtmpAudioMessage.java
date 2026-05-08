package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpAudioMessage extends RtmpMessage {

    private final int soundFormat;
    private final int soundRate;
    private final int soundSize;
    private final int soundType;
    private final Integer aacPacketType;

    public RtmpAudioMessage(int chunkStreamId, long timestamp, int messageStreamId, byte[] payload) {
        this(chunkStreamId, timestamp, messageStreamId, payload, parseSoundFormat(payload), parseSoundRate(payload), parseSoundSize(payload), parseSoundType(payload), parseAacPacketType(payload));
    }

    public RtmpAudioMessage(
            int chunkStreamId,
            long timestamp,
            int messageStreamId,
            byte[] payload,
            int soundFormat,
            int soundRate,
            int soundSize,
            int soundType,
            Integer aacPacketType
    ) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.AUDIO, payload);
        this.soundFormat = soundFormat;
        this.soundRate = soundRate;
        this.soundSize = soundSize;
        this.soundType = soundType;
        this.aacPacketType = aacPacketType;
    }

    public int soundFormat() {
        return soundFormat;
    }

    public int soundRate() {
        return soundRate;
    }

    public int soundSize() {
        return soundSize;
    }

    public int soundType() {
        return soundType;
    }

    public Integer aacPacketType() {
        return aacPacketType;
    }

    private static int parseSoundFormat(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : ((payload[0] & 0xFF) >> 4) & 0x0F;
    }

    private static int parseSoundRate(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : ((payload[0] & 0xFF) >> 2) & 0x03;
    }

    private static int parseSoundSize(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : ((payload[0] & 0xFF) >> 1) & 0x01;
    }

    private static int parseSoundType(byte[] payload) {
        return payload == null || payload.length == 0 ? -1 : (payload[0] & 0x01);
    }

    private static Integer parseAacPacketType(byte[] payload) {
        return parseSoundFormat(payload) == 10 && payload.length > 1 ? Integer.valueOf(payload[1] & 0xFF) : null;
    }
}
