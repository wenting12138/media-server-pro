package com.wenting.mediaserver.protocol.http;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.track.ITrack;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpFlvSubscriberSession {

    private static final byte[] FLV_HEADER = new byte[]{
            'F', 'L', 'V',
            0x01,
            0x05,
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00
    };

    private final String sessionId;
    private final StreamKey streamKey;
    private final Channel channel;
    private final RtpToRtmpFrameAssembler rtpToRtmpFrameAssembler = new RtpToRtmpFrameAssembler();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();
    private boolean responseStarted;

    public HttpFlvSubscriberSession(String sessionId, StreamKey streamKey, Channel channel) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.channel = channel;
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public void track(ITrack track) {
        if (track == null || track.trackId() == null) {
            return;
        }
        tracksById.put(track.trackId().trim(), track);
    }

    public void startResponse() {
        if (!isActive() || responseStarted) {
            return;
        }
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        response.headers().set("Access-Control-Allow-Origin", "*");
        HttpUtil.setTransferEncodingChunked(response, true);
        channel.write(response);
        channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(FLV_HEADER)));
        responseStarted = true;
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
        if (!responseStarted) {
            startResponse();
        }
        if (frame.trackType() == TrackType.VIDEO) {
            writeFlvTag((byte) 0x09, toVideoPayload(frame), resolveTimestamp(frame));
            return;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            writeFlvTag((byte) 0x08, toAudioPayload(frame), resolveTimestamp(frame));
        }
    }

    private void writeFlvTag(byte tagType, byte[] payload, long timestamp) {
        channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(FlvTagBuilder.build(tagType, payload, timestamp))));
    }

    private byte[] toVideoPayload(InboundMediaFrame frame) {
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

    private byte[] toAudioPayload(InboundMediaFrame frame) {
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
