package com.wenting.mediaserver.protocol.http;

final class FlvTagBuilder {

    private FlvTagBuilder() {
    }

    static byte[] build(byte tagType, byte[] payload, long timestamp) {
        int payloadLength = payload == null ? 0 : payload.length;
        int tagLength = 11 + payloadLength + 4;
        byte[] tag = new byte[tagLength];
        tag[0] = tagType;
        tag[1] = (byte) ((payloadLength >> 16) & 0xFF);
        tag[2] = (byte) ((payloadLength >> 8) & 0xFF);
        tag[3] = (byte) (payloadLength & 0xFF);
        int ts = (int) Math.max(0L, Math.min(timestamp, 0xFFFFFFFFL));
        tag[4] = (byte) ((ts >> 16) & 0xFF);
        tag[5] = (byte) ((ts >> 8) & 0xFF);
        tag[6] = (byte) (ts & 0xFF);
        tag[7] = (byte) ((ts >> 24) & 0xFF);
        tag[8] = 0x00;
        tag[9] = 0x00;
        tag[10] = 0x00;
        if (payloadLength > 0) {
            System.arraycopy(payload, 0, tag, 11, payloadLength);
        }
        int previousTagSize = 11 + payloadLength;
        int offset = 11 + payloadLength;
        tag[offset] = (byte) ((previousTagSize >> 24) & 0xFF);
        tag[offset + 1] = (byte) ((previousTagSize >> 16) & 0xFF);
        tag[offset + 2] = (byte) ((previousTagSize >> 8) & 0xFF);
        tag[offset + 3] = (byte) (previousTagSize & 0xFF);
        return tag;
    }
}
