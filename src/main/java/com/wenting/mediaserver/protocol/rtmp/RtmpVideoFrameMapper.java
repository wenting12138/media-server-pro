package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;

import java.net.InetSocketAddress;
import java.util.Arrays;

public final class RtmpVideoFrameMapper {

    public InboundMediaFrame map(RtmpSession session, RtmpVideoMessage message, InetSocketAddress remoteAddress) {
        if (session == null || message == null || session.streamKey() == null) {
            return null;
        }
        byte[] payload = message.payload();
        if (payload.length < 1) {
            return null;
        }
        CodecType codecType = resolveCodecType(message.codecId());
        Long dtsMillis = Long.valueOf(message.timestamp());
        Long ptsMillis = dtsMillis;
        if (message.compositionTime() != null) {
            ptsMillis = Long.valueOf(message.timestamp() + message.compositionTime().intValue());
        }
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                codecType,
                session.sessionId(),
                session.streamKey(),
                trackId(codecType),
                ptsMillis,
                dtsMillis,
                isKeyFrame(message),
                isConfigFrame(message),
                remoteAddress,
                extractMediaPayload(message, payload)
        );
    }

    private CodecType resolveCodecType(int codecId) {
        if (codecId == 7) {
            return CodecType.H264;
        }
        if (codecId == 12) {
            return CodecType.H265;
        }
        return CodecType.UNKNOWN;
    }

    private boolean isKeyFrame(RtmpVideoMessage message) {
        return message.frameType() == 1;
    }

    private boolean isConfigFrame(RtmpVideoMessage message) {
        return message.avcPacketType() != null && message.avcPacketType().intValue() == 0;
    }

    private String trackId(CodecType codecType) {
        if (codecType == CodecType.H264) {
            return "video-h264";
        }
        if (codecType == CodecType.H265) {
            return "video-h265";
        }
        return "video";
    }

    private byte[] extractMediaPayload(RtmpVideoMessage message, byte[] payload) {
        if ((message.codecId() == 7 || message.codecId() == 12) && payload.length > 5) {
            return Arrays.copyOfRange(payload, 5, payload.length);
        }
        return payload.length <= 1 ? new byte[0] : Arrays.copyOfRange(payload, 1, payload.length);
    }
}
