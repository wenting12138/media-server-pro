package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;

import java.net.InetSocketAddress;
import java.util.Arrays;

public final class RtmpAudioFrameMapper {

    public InboundMediaFrame map(RtmpSession session, RtmpAudioMessage message, InetSocketAddress remoteAddress) {
        if (session == null || message == null || session.streamKey() == null) {
            return null;
        }
        byte[] payload = message.payload();
        if (payload.length < 1) {
            return null;
        }
        CodecType codecType = resolveCodecType(message.soundFormat());
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                codecType,
                session.sessionId(),
                session.streamKey(),
                trackId(codecType),
                Long.valueOf(message.timestamp()),
                Long.valueOf(message.timestamp()),
                false,
                isConfigFrame(message),
                remoteAddress,
                extractMediaPayload(message, payload)
        );
    }

    private CodecType resolveCodecType(int soundFormat) {
        if (soundFormat == 10) {
            return CodecType.AAC;
        }
        if (soundFormat == 7) {
            return CodecType.G711A;
        }
        if (soundFormat == 8) {
            return CodecType.G711U;
        }
        return CodecType.UNKNOWN;
    }

    private boolean isConfigFrame(RtmpAudioMessage message) {
        return message.aacPacketType() != null && message.aacPacketType().intValue() == 0;
    }

    private String trackId(CodecType codecType) {
        if (codecType == CodecType.AAC) {
            return "audio-aac";
        }
        if (codecType == CodecType.G711A) {
            return "audio-g711a";
        }
        if (codecType == CodecType.G711U) {
            return "audio-g711u";
        }
        return "audio";
    }

    private byte[] extractMediaPayload(RtmpAudioMessage message, byte[] payload) {
        if (message.soundFormat() == 10) {
            return payload.length <= 2 ? new byte[0] : Arrays.copyOfRange(payload, 2, payload.length);
        }
        return payload.length <= 1 ? new byte[0] : Arrays.copyOfRange(payload, 1, payload.length);
    }
}
