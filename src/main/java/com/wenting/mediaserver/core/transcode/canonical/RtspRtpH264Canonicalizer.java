package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RtspRtpH264Canonicalizer implements VideoFrameCanonicalizer {

    private static final int H264_NAL_TYPE_STAP_A = 24;
    private static final int H264_NAL_TYPE_FU_A = 28;
    private static final int H264_NAL_TYPE_SPS = 7;
    private static final int H264_NAL_TYPE_PPS = 8;
    private static final int H264_NAL_TYPE_IDR = 5;

    private final RtpPacketParser rtpPacketParser = new RtpPacketParser();
    private final Map<String, TrackState> statesByTrackId = new ConcurrentHashMap<String, TrackState>();

    @Override
    public CanonicalVideoFrame canonicalize(InboundMediaFrame frame) {
        return null;
    }

    @Override
    public List<CanonicalVideoFrame> canonicalize(InboundRtpPacket packet, ITrack track) {
        if (packet == null || packet.rtcp() || packet.frame() == null) {
            return Collections.emptyList();
        }
        InboundMediaFrame frame = packet.frame();
        if ((frame.sourceProtocol() != StreamProtocol.RTSP && frame.sourceProtocol() != StreamProtocol.WEBRTC)
                || frame.trackType() != TrackType.VIDEO
                || frame.codecType() != CodecType.H264) {
            return Collections.emptyList();
        }
        RtpParseResult parseResult = rtpPacketParser.parse(frame.payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null || header.payloadLength() <= 0) {
            return Collections.emptyList();
        }
        TrackState state = state(frame.trackId());
        primeParameterSets(state, track);
        byte[] payload = frame.payload();
        int payloadOffset = header.payloadOffset();
        int nalType = payload[payloadOffset] & 0x1F;
        if (nalType == H264_NAL_TYPE_SPS) {
            state.sps = copy(payload, payloadOffset, header.payloadLength());
            state.configDirty = true;
            return Collections.emptyList();
        }
        if (nalType == H264_NAL_TYPE_PPS) {
            state.pps = copy(payload, payloadOffset, header.payloadLength());
            state.configDirty = true;
            return Collections.emptyList();
        }
        if (nalType == H264_NAL_TYPE_STAP_A) {
            state.beginAccessUnit(header.timestamp());
            boolean hasVideoNal = cacheAndAppendStapA(state, payload, payloadOffset + 1, header.payloadLength() - 1);
            if (!hasVideoNal || !header.marker()) {
                return Collections.emptyList();
            }
            return emitCompletedAccessUnit(packet, state, header.timestamp());
        }
        long timestamp = header.timestamp();
        state.beginAccessUnit(timestamp);
        if (nalType == H264_NAL_TYPE_FU_A) {
            int fuHeader = payload[payloadOffset + 1] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int originalNalType = fuHeader & 0x1F;
            if (start) {
                byte reconstructedHeader = (byte) ((payload[payloadOffset] & 0xE0) | originalNalType);
                state.beginPendingFragment(timestamp, new byte[]{reconstructedHeader});
            }
            if (state.pendingFragmentBuffer == null || !Long.valueOf(timestamp).equals(state.pendingFragmentTimestamp)) {
                state.clearPendingFragment();
                return Collections.emptyList();
            }
            state.pendingFragmentBuffer.write(payload, payloadOffset + 2, header.payloadLength() - 2);
            if (!end) {
                return Collections.emptyList();
            }
            byte[] nal = state.completePendingFragment();
            state.addNalToAccessUnit(nal, originalNalType == H264_NAL_TYPE_IDR);
        } else {
            state.addNalToAccessUnit(copy(payload, payloadOffset, header.payloadLength()), nalType == H264_NAL_TYPE_IDR);
        }
        if (!header.marker()) {
            return Collections.emptyList();
        }
        return emitCompletedAccessUnit(packet, state, header.timestamp());
    }

    @Override
    public void close() {
        statesByTrackId.clear();
    }

    private List<CanonicalVideoFrame> emitCompletedAccessUnit(InboundRtpPacket packet, TrackState state, long rtpTimestamp) {
        if (state.currentAccessUnitNals.isEmpty()) {
            state.clearAccessUnit();
            return Collections.emptyList();
        }
        List<CanonicalVideoFrame> frames = new ArrayList<CanonicalVideoFrame>(2);
        byte[] configPayload = buildConfigPayload(state);
        H264CodecConfig codecConfig = buildCodecConfig(state);
        Long timestampMillis = toTimestampMillis(state, packet.clockRate(), rtpTimestamp);
        if (state.currentAccessUnitKeyFrame && configPayload != null && state.shouldSendConfigBeforeKeyFrame()) {
            InboundMediaFrame configFrame = new InboundMediaFrame(
                    packet.frame().sourceProtocol(),
                    TrackType.VIDEO,
                    CodecType.H264,
                    packet.frame().sessionId(),
                    packet.frame().streamKey(),
                    packet.frame().trackId(),
                    timestampMillis,
                    timestampMillis,
                    true,
                    true,
                    packet.frame().remoteAddress(),
                    configPayload
            );
            frames.add(new CanonicalVideoFrame(
                    configFrame,
                    VideoPayloadFormat.H264_AVCC,
                    configPayload,
                    true,
                    true,
                    codecConfig
            ));
            state.markConfigSent();
        }
        byte[] accessUnitPayload = buildLengthPrefixedPayload(state.currentAccessUnitNals);
        InboundMediaFrame mediaFrame = new InboundMediaFrame(
                packet.frame().sourceProtocol(),
                TrackType.VIDEO,
                CodecType.H264,
                packet.frame().sessionId(),
                packet.frame().streamKey(),
                packet.frame().trackId(),
                timestampMillis,
                timestampMillis,
                state.currentAccessUnitKeyFrame,
                false,
                packet.frame().remoteAddress(),
                accessUnitPayload
        );
        frames.add(new CanonicalVideoFrame(
                mediaFrame,
                VideoPayloadFormat.H264_AVCC,
                accessUnitPayload,
                state.currentAccessUnitKeyFrame,
                false,
                codecConfig
        ));
        state.clearAccessUnit();
        return frames;
    }

    private void primeParameterSets(TrackState state, ITrack track) {
        if (track == null) {
            return;
        }
        if (state.sps == null && track.h264Sps() != null) {
            state.sps = track.h264Sps();
            state.configDirty = true;
        }
        if (state.pps == null && track.h264Pps() != null) {
            state.pps = track.h264Pps();
            state.configDirty = true;
        }
    }

    private boolean cacheAndAppendStapA(TrackState state, byte[] payload, int offset, int length) {
        int cursor = offset;
        int limit = offset + length;
        boolean hasVideoNal = false;
        while (cursor + 2 <= limit) {
            int nalLength = ((payload[cursor] & 0xFF) << 8) | (payload[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalLength <= 0 || cursor + nalLength > limit) {
                return hasVideoNal;
            }
            int nalType = payload[cursor] & 0x1F;
            byte[] nal = copy(payload, cursor, nalLength);
            if (nalType == H264_NAL_TYPE_SPS) {
                state.sps = nal;
                state.configDirty = true;
            } else if (nalType == H264_NAL_TYPE_PPS) {
                state.pps = nal;
                state.configDirty = true;
            } else {
                state.addNalToAccessUnit(nal, nalType == H264_NAL_TYPE_IDR);
                hasVideoNal = true;
            }
            cursor += nalLength;
        }
        return hasVideoNal;
    }

    private H264CodecConfig buildCodecConfig(TrackState state) {
        if (state.sps == null || state.pps == null || state.sps.length < 4) {
            return null;
        }
        return new H264CodecConfig(4, state.sps, state.pps, toHex(state.sps[1]) + toHex(state.sps[2]) + toHex(state.sps[3]));
    }

    private byte[] buildConfigPayload(TrackState state) {
        if (state.sps == null || state.pps == null || state.sps.length < 4) {
            return null;
        }
        byte[] sps = state.sps;
        byte[] pps = state.pps;
        byte[] config = new byte[11 + sps.length + pps.length];
        int index = 0;
        config[index++] = 0x01;
        config[index++] = sps[1];
        config[index++] = sps[2];
        config[index++] = sps[3];
        config[index++] = (byte) 0xFF;
        config[index++] = (byte) 0xE1;
        config[index++] = (byte) ((sps.length >> 8) & 0xFF);
        config[index++] = (byte) (sps.length & 0xFF);
        System.arraycopy(sps, 0, config, index, sps.length);
        index += sps.length;
        config[index++] = 0x01;
        config[index++] = (byte) ((pps.length >> 8) & 0xFF);
        config[index++] = (byte) (pps.length & 0xFF);
        System.arraycopy(pps, 0, config, index, pps.length);
        return config;
    }

    private byte[] buildLengthPrefixedPayload(List<byte[]> nals) {
        int totalLength = 0;
        for (byte[] nal : nals) {
            totalLength += 4 + nal.length;
        }
        byte[] payload = new byte[totalLength];
        int index = 0;
        for (byte[] nal : nals) {
            payload[index++] = (byte) ((nal.length >> 24) & 0xFF);
            payload[index++] = (byte) ((nal.length >> 16) & 0xFF);
            payload[index++] = (byte) ((nal.length >> 8) & 0xFF);
            payload[index++] = (byte) (nal.length & 0xFF);
            System.arraycopy(nal, 0, payload, index, nal.length);
            index += nal.length;
        }
        return payload;
    }

    private Long toTimestampMillis(TrackState state, int clockRate, long rtpTimestamp) {
        if (state.firstRtpTimestamp == null) {
            state.firstRtpTimestamp = Long.valueOf(rtpTimestamp);
        }
        long delta = (rtpTimestamp - state.firstRtpTimestamp.longValue()) & 0xFFFFFFFFL;
        return Long.valueOf((delta * 1000L) / Math.max(clockRate, 1));
    }

    private TrackState state(String trackId) {
        String normalized = trackId == null ? "" : trackId.trim();
        TrackState existing = statesByTrackId.get(normalized);
        if (existing != null) {
            return existing;
        }
        TrackState created = new TrackState();
        TrackState previous = statesByTrackId.putIfAbsent(normalized, created);
        return previous == null ? created : previous;
    }

    private byte[] copy(byte[] payload, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(payload, offset, copy, 0, length);
        return copy;
    }

    private String toHex(byte value) {
        int unsigned = value & 0xFF;
        String hex = Integer.toHexString(unsigned);
        return hex.length() < 2 ? "0" + hex : hex;
    }


}
