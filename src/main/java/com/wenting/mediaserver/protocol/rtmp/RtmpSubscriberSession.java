package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.track.ITrack;
import io.netty.channel.Channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RTMP subscriber write session for outbound live playback.
 */
public final class RtmpSubscriberSession {

    private static final int AUDIO_CHUNK_STREAM_ID = 4;
    private static final int VIDEO_CHUNK_STREAM_ID = 6;
    private static final int DEFAULT_MESSAGE_STREAM_ID = 1;

    private final String sessionId;
    private final StreamKey streamKey;
    private final Channel controlChannel;
    private final int messageStreamId;
    private final RtpToRtmpFrameAssembler rtpToRtmpFrameAssembler = new RtpToRtmpFrameAssembler();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();

    public RtmpSubscriberSession(String sessionId, StreamKey streamKey, Channel controlChannel, Integer messageStreamId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (controlChannel == null) {
            throw new IllegalArgumentException("controlChannel must not be null");
        }
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.controlChannel = controlChannel;
        this.messageStreamId = messageStreamId == null || messageStreamId.intValue() <= 0
                ? DEFAULT_MESSAGE_STREAM_ID
                : messageStreamId.intValue();
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public Channel controlChannel() {
        return controlChannel;
    }

    public int messageStreamId() {
        return messageStreamId;
    }

    public boolean isActive() {
        return controlChannel.isActive();
    }

    public void track(ITrack track) {
        if (track == null || track.trackId() == null) {
            return;
        }
        tracksById.put(track.trackId().trim(), track);
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || !isActive()) {
            return;
        }
        List<InboundMediaFrame> frames = rtpToRtmpFrameAssembler.assemble(packet, tracksById.get(packet.frame().trackId()));
        for (InboundMediaFrame frame : frames) {
            writeInboundFrame(frame);
        }
    }

    public void writeInboundFrame(InboundMediaFrame frame) {
        if (frame == null || !isActive()) {
            return;
        }
        if (frame.trackType() == TrackType.VIDEO) {
            controlChannel.writeAndFlush(toVideoMessage(frame));
            return;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            controlChannel.writeAndFlush(toAudioMessage(frame));
        }
    }

    private RtmpVideoMessage toVideoMessage(InboundMediaFrame frame) {
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
        return new RtmpVideoMessage(
                VIDEO_CHUNK_STREAM_ID,
                resolveTimestamp(frame),
                messageStreamId,
                payload
        );
    }

    private RtmpAudioMessage toAudioMessage(InboundMediaFrame frame) {
        int soundFormat = resolveAudioFormat(frame.codecType());
        int soundHeader = resolveAudioHeader(soundFormat);
        int extraHeaderSize = soundFormat == 10 ? 1 : 0;
        byte[] payload = new byte[1 + extraHeaderSize + frame.payloadLength()];
        payload[0] = (byte) (soundHeader & 0xFF);
        if (soundFormat == 10) {
            payload[1] = (byte) (frame.configFrame() ? 0 : 1);
        }
        System.arraycopy(frame.payload(), 0, payload, 1 + extraHeaderSize, frame.payloadLength());
        return new RtmpAudioMessage(
                AUDIO_CHUNK_STREAM_ID,
                resolveTimestamp(frame),
                messageStreamId,
                payload
        );
    }

    private long resolveTimestamp(InboundMediaFrame frame) {
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
        if (codecType == CodecType.H264) {
            return 7;
        }
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
