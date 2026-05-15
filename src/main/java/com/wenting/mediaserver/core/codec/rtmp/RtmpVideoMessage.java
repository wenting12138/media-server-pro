package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpVideoMessage extends RtmpMessage {

    private final int frameType;
    private final int codecId;
    private final Integer avcPacketType;
    private final Integer compositionTime;
    private final boolean enhancedVideoHeader;
    private final Integer videoPacketType;
    private final String videoFourCc;

    public RtmpVideoMessage(int chunkStreamId, long timestamp, int messageStreamId, byte[] payload) {
        this(
                chunkStreamId,
                timestamp,
                messageStreamId,
                payload,
                parseFrameType(payload),
                parseCodecId(payload),
                parseAvcPacketType(payload),
                parseCompositionTime(payload),
                isEnhancedVideoHeader(payload),
                parseVideoPacketType(payload),
                parseVideoFourCc(payload)
        );
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
        this(
                chunkStreamId,
                timestamp,
                messageStreamId,
                payload,
                frameType,
                codecId,
                avcPacketType,
                compositionTime,
                isEnhancedVideoHeader(payload),
                parseVideoPacketType(payload),
                parseVideoFourCc(payload)
        );
    }

    private RtmpVideoMessage(
            int chunkStreamId,
            long timestamp,
            int messageStreamId,
            byte[] payload,
            int frameType,
            int codecId,
            Integer avcPacketType,
            Integer compositionTime,
            boolean enhancedVideoHeader,
            Integer videoPacketType,
            String videoFourCc
    ) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.VIDEO, payload);
        this.frameType = frameType;
        this.codecId = codecId;
        this.avcPacketType = avcPacketType;
        this.compositionTime = compositionTime;
        this.enhancedVideoHeader = enhancedVideoHeader;
        this.videoPacketType = videoPacketType;
        this.videoFourCc = videoFourCc;
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

    public boolean enhancedVideoHeader() {
        return enhancedVideoHeader;
    }

    public Integer videoPacketType() {
        return videoPacketType;
    }

    public String videoFourCc() {
        return videoFourCc;
    }

    private static int parseFrameType(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return -1;
        }
        int header = payload[0] & 0xFF;
        if (isEnhancedVideoHeader(payload)) {
            return (header >> 4) & 0x07;
        }
        return (header >> 4) & 0x0F;
    }

    private static int parseCodecId(byte[] payload) {
        if (isEnhancedVideoHeader(payload)) {
            return -1;
        }
        return payload == null || payload.length == 0 ? -1 : (payload[0] & 0x0F);
    }

    private static Integer parseAvcPacketType(byte[] payload) {
        return payload != null && payload.length > 1 ? Integer.valueOf(payload[1] & 0xFF) : null;
    }

    private static Integer parseCompositionTime(byte[] payload) {
        if (payload == null) {
            return null;
        }
        if (isEnhancedVideoHeader(payload)) {
            Integer videoPacketType = parseVideoPacketType(payload);
            if (videoPacketType == null) {
                return null;
            }
            if (videoPacketType.intValue() == 1 && payload.length >= 8) {
                return Integer.valueOf(parseSigned24(payload, 5));
            }
            if (videoPacketType.intValue() == 3) {
                return Integer.valueOf(0);
            }
            return null;
        }
        if (payload == null || payload.length < 5) {
            return null;
        }
        return Integer.valueOf(parseSigned24(payload, 2));
    }

    private static boolean isEnhancedVideoHeader(byte[] payload) {
        return payload != null && payload.length > 0 && ((payload[0] & 0x80) != 0);
    }

    private static Integer parseVideoPacketType(byte[] payload) {
        return isEnhancedVideoHeader(payload) ? Integer.valueOf(payload[0] & 0x0F) : null;
    }

    private static String parseVideoFourCc(byte[] payload) {
        if (!isEnhancedVideoHeader(payload) || payload.length < 5) {
            return null;
        }
        return new String(payload, 1, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int parseSigned24(byte[] payload, int offset) {
        int value = ((payload[offset] & 0xFF) << 16) | ((payload[offset + 1] & 0xFF) << 8) | (payload[offset + 2] & 0xFF);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }
}
