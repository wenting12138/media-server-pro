package com.wenting.mediaserver.protocol.http.flv;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;

final class FlvPayloadBuilder {

    byte[] toVideoPayload(InboundMediaFrame frame) {
        int codecId = resolveVideoCodecId(frame.codecType());
        int frameType = frame.keyFrame() ? 1 : 2;
        int packetType = frame.configFrame() ? 0 : 1;
        int compositionTime = resolveCompositionTime(frame);
        byte[] payload = new byte[5 + frame.payloadLength()];
        payload[0] = (byte) (((frameType & 0x0F) << 4) | (codecId & 0x0F));
        payload[1] = (byte) (packetType & 0xFF);
        payload[2] = (byte) ((compositionTime >> 16) & 0xFF);
        payload[3] = (byte) ((compositionTime >> 8) & 0xFF);
        payload[4] = (byte) (compositionTime & 0xFF);
        System.arraycopy(frame.payload(), 0, payload, 5, frame.payloadLength());
        return payload;
    }

    byte[] toAudioPayload(InboundMediaFrame frame) {
        int soundFormat = resolveAudioFormat(frame.codecType());
        int soundHeader = resolveAudioHeader(soundFormat);
        int extraHeaderSize = soundFormat == 10 ? 1 : 0;
        byte[] payload = new byte[1 + extraHeaderSize + frame.payloadLength()];
        payload[0] = (byte) (soundHeader & 0xFF);
        if (soundFormat == 10) {
            payload[1] = (byte) (frame.configFrame() ? 0 : 1);
        }
        System.arraycopy(frame.payload(), 0, payload, 1 + extraHeaderSize, frame.payloadLength());
        return payload;
    }

    long resolveTimestamp(InboundMediaFrame frame) {
        Long dts = frame.dtsMillis();
        Long pts = frame.ptsMillis();
        if (dts != null && dts.longValue() >= 0L) {
            return dts.longValue();
        }
        return pts == null || pts.longValue() < 0L ? 0L : pts.longValue();
    }

    private int resolveCompositionTime(InboundMediaFrame frame) {
        Long pts = frame.ptsMillis();
        Long dts = frame.dtsMillis();
        if (pts == null || dts == null) {
            return 0;
        }
        long delta = pts.longValue() - dts.longValue();
        if (delta > 0x7FFFFFL) {
            return 0x7FFFFF;
        }
        if (delta < -0x800000L) {
            return -0x800000;
        }
        return (int) delta;
    }

    private int resolveVideoCodecId(CodecType codecType) {
        if (codecType == CodecType.H265) {
            return 12;
        }
        return 7;
    }

    private int resolveAudioFormat(CodecType codecType) {
        if (codecType == CodecType.AAC || codecType == CodecType.MPEG4_GENERIC) {
            return 10;
        }
        if (codecType == CodecType.G711A) {
            return 7;
        }
        if (codecType == CodecType.G711U) {
            return 8;
        }
        return 10;
    }

    private int resolveAudioHeader(int soundFormat) {
        if (soundFormat == 10) {
            return 0xAF;
        }
        if (soundFormat == 7) {
            return 0x72;
        }
        if (soundFormat == 8) {
            return 0x82;
        }
        return 0xAF;
    }
}
