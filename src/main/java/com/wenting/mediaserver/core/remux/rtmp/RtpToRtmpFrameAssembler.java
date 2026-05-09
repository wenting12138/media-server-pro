package com.wenting.mediaserver.core.remux.rtmp;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RtpToRtmpFrameAssembler {

    private static final int H264_NAL_TYPE_STAP_A = 24;
    private static final int H264_NAL_TYPE_FU_A = 28;
    private static final int H264_NAL_TYPE_SPS = 7;
    private static final int H264_NAL_TYPE_PPS = 8;
    private static final int H264_NAL_TYPE_IDR = 5;

    private static final int H265_NAL_TYPE_AP = 48;
    private static final int H265_NAL_TYPE_FU = 49;
    private static final int H265_NAL_TYPE_VPS = 32;
    private static final int H265_NAL_TYPE_SPS = 33;
    private static final int H265_NAL_TYPE_PPS = 34;

    private final RtpPacketParser rtpPacketParser = new RtpPacketParser();
    private final Map<String, RtpToRtmpTrackState> statesByTrackId = new ConcurrentHashMap<String, RtpToRtmpTrackState>();

    public List<InboundMediaFrame> assemble(InboundRtpPacket packet, ITrack track) {
        if (packet == null || packet.rtcp() || packet.frame() == null) {
            return Collections.emptyList();
        }
        InboundMediaFrame frame = packet.frame();
        if (frame.trackType() == TrackType.AUDIO) {
            if(frame.codecType() == CodecType.AAC || frame.codecType() == CodecType.MPEG4_GENERIC){
                return assembleAac(packet);
            }
        }
        if (frame.trackType() == TrackType.VIDEO) {
            RtpParseResult parseResult = rtpPacketParser.parse(frame.payload());
            RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
            if (header == null || header.payloadLength() <= 0) {
                return Collections.emptyList();
            }
            String trackId = frame.trackId() == null ? "" : frame.trackId().trim();
            RtpToRtmpTrackState state = state(trackId);
            if (frame.codecType() == CodecType.H264) {
                return assembleH264(packet, header, state, track);
            }
            if (frame.codecType() == CodecType.H265) {
                return assembleH265(packet, header, state, track);
            }
        }
        return Collections.emptyList();
    }

    private List<InboundMediaFrame> assembleH264(InboundRtpPacket packet, RtpPacketHeader header, RtpToRtmpTrackState state, ITrack track) {
        byte[] payload = packet.frame().payload();
        int payloadOffset = header.payloadOffset();
        int nalType = payload[payloadOffset] & 0x1F;
        primeH264ParameterSetsFromTrack(state, track);
        if (nalType == H264_NAL_TYPE_SPS) {
            state.h264Sps(copy(payload, payloadOffset, header.payloadLength()));
            return Collections.emptyList();
        }
        if (nalType == H264_NAL_TYPE_PPS) {
            state.h264Pps(copy(payload, payloadOffset, header.payloadLength()));
            return Collections.emptyList();
        }
        if (nalType == H264_NAL_TYPE_STAP_A) {
            state.beginAccessUnit(header.timestamp());
            boolean hasVideoNal = cacheAndAppendH264StapA(state, payload, payloadOffset + 1, header.payloadLength() - 1);
            if (!hasVideoNal || !header.marker()) {
                return Collections.emptyList();
            }
            return emitCompletedVideoAccessUnit(packet, state, buildH264Config(state), header.timestamp());
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
                state.beginPendingFragment(timestamp, originalNalType, new byte[]{reconstructedHeader});
            }
            if (state.pendingFragmentBuffer() == null || !Long.valueOf(timestamp).equals(state.pendingFragmentTimestamp())) {
                state.clearPendingFragment();
                return Collections.emptyList();
            }
            state.appendPendingFragment(payload, payloadOffset + 2, header.payloadLength() - 2);
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
        return emitCompletedVideoAccessUnit(packet, state, buildH264Config(state), header.timestamp());
    }

    private List<InboundMediaFrame> assembleH265(InboundRtpPacket packet, RtpPacketHeader header, RtpToRtmpTrackState state, ITrack track) {
        byte[] payload = packet.frame().payload();
        int payloadOffset = header.payloadOffset();
        int nalType = (payload[payloadOffset] & 0x7E) >> 1;
        primeH265ParameterSetsFromTrack(state, track);
        if (nalType == H265_NAL_TYPE_VPS) {
            state.h265Vps(copy(payload, payloadOffset, header.payloadLength()));
            return Collections.emptyList();
        }
        if (nalType == H265_NAL_TYPE_SPS) {
            state.h265Sps(copy(payload, payloadOffset, header.payloadLength()));
            return Collections.emptyList();
        }
        if (nalType == H265_NAL_TYPE_PPS) {
            state.h265Pps(copy(payload, payloadOffset, header.payloadLength()));
            return Collections.emptyList();
        }
        if (nalType == H265_NAL_TYPE_AP) {
            state.beginAccessUnit(header.timestamp());
            boolean hasVideoNal = cacheAndAppendH265Ap(state, payload, payloadOffset + 2, header.payloadLength() - 2);
            if (!hasVideoNal || !header.marker()) {
                return Collections.emptyList();
            }
            return emitCompletedVideoAccessUnit(packet, state, buildH265Config(state), header.timestamp());
        }
        long timestamp = header.timestamp();
        state.beginAccessUnit(timestamp);
        if (nalType == H265_NAL_TYPE_FU) {
            int fuHeader = payload[payloadOffset + 2] & 0xFF;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int originalNalType = fuHeader & 0x3F;
            if (start) {
                byte[] nalHeader = new byte[]{
                        (byte) ((payload[payloadOffset] & 0x81) | (originalNalType << 1)),
                        payload[payloadOffset + 1]
                };
                state.beginPendingFragment(timestamp, originalNalType, nalHeader);
            }
            if (state.pendingFragmentBuffer() == null || !Long.valueOf(timestamp).equals(state.pendingFragmentTimestamp())) {
                state.clearPendingFragment();
                return Collections.emptyList();
            }
            state.appendPendingFragment(payload, payloadOffset + 3, header.payloadLength() - 3);
            if (!end) {
                return Collections.emptyList();
            }
            byte[] nal = state.completePendingFragment();
            state.addNalToAccessUnit(nal, isH265KeyFrameNalType(originalNalType));
        } else {
            state.addNalToAccessUnit(copy(payload, payloadOffset, header.payloadLength()), isH265KeyFrameNalType(nalType));
        }
        if (!header.marker()) {
            return Collections.emptyList();
        }
        return emitCompletedVideoAccessUnit(packet, state, buildH265Config(state), header.timestamp());
    }

    private List<InboundMediaFrame> assembleAac(InboundRtpPacket packet) {
        byte[] payload = packet.frame().payload();
        RtpParseResult parseResult = rtpPacketParser.parse(payload);
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null || header.payloadLength() <= 4) {
            return Collections.emptyList();
        }
        int payloadOffset = header.payloadOffset();
        int auHeadersLengthBits = ((payload[payloadOffset] & 0xFF) << 8) | (payload[payloadOffset + 1] & 0xFF);
        int auHeadersLengthBytes = (auHeadersLengthBits + 7) / 8;
        if (auHeadersLengthBits < 16 || payloadOffset + 2 + auHeadersLengthBytes > payload.length) {
            return Collections.emptyList();
        }
        int auHeaderOffset = payloadOffset + 2;
        int auSizeBits = ((payload[auHeaderOffset] & 0xFF) << 8) | (payload[auHeaderOffset + 1] & 0xFF);
        int auSizeBytes = auSizeBits >> 3;
        int dataOffset = auHeaderOffset + auHeadersLengthBytes;
        if (auSizeBytes <= 0 || dataOffset + auSizeBytes > payload.length) {
            return Collections.emptyList();
        }
        String trackId = packet.frame().trackId() == null ? "" : packet.frame().trackId().trim();
        RtpToRtmpTrackState state = state(trackId);
        if (state.aacConfig() == null) {
            state.aacConfig(buildAacConfig(packet.clockRate(), 2));
        }
        Long timestampMillis = toTimestampMillis(state, packet.clockRate(), header.timestamp());
        List<InboundMediaFrame> frames = new ArrayList<InboundMediaFrame>(2);
        if (state.shouldSendAacConfig()) {
            frames.add(new InboundMediaFrame(
                    StreamProtocol.RTSP,
                    TrackType.AUDIO,
                    CodecType.AAC,
                    packet.frame().sessionId(),
                    packet.frame().streamKey(),
                    packet.frame().trackId(),
                    timestampMillis,
                    timestampMillis,
                    false,
                    true,
                    packet.frame().remoteAddress(),
                    state.aacConfig()
            ));
            state.markAacConfigSent();
        }
        frames.add(new InboundMediaFrame(
                StreamProtocol.RTSP,
                TrackType.AUDIO,
                CodecType.AAC,
                packet.frame().sessionId(),
                packet.frame().streamKey(),
                packet.frame().trackId(),
                timestampMillis,
                timestampMillis,
                false,
                false,
                packet.frame().remoteAddress(),
                copy(payload, dataOffset, auSizeBytes)
        ));
        return frames;
    }

    private List<InboundMediaFrame> emitCompletedVideoAccessUnit(
            InboundRtpPacket packet,
            RtpToRtmpTrackState state,
            byte[] configPayload,
            long rtpTimestamp
    ) {
        if (state.currentAccessUnitNals().isEmpty()) {
            state.clearAccessUnit();
            return Collections.emptyList();
        }
        List<InboundMediaFrame> frames = new ArrayList<InboundMediaFrame>(2);
        Long timestampMillis = toTimestampMillis(state, packet.clockRate(), rtpTimestamp);
        if (state.currentAccessUnitKeyFrame() && configPayload != null && state.shouldSendConfigBeforeKeyFrame()) {
            frames.add(new InboundMediaFrame(
                    StreamProtocol.RTSP,
                    TrackType.VIDEO,
                    packet.frame().codecType(),
                    packet.frame().sessionId(),
                    packet.frame().streamKey(),
                    packet.frame().trackId(),
                    timestampMillis,
                    timestampMillis,
                    true,
                    true,
                    packet.frame().remoteAddress(),
                    configPayload
            ));
            state.markConfigSent();
        }
        frames.add(new InboundMediaFrame(
                StreamProtocol.RTSP,
                TrackType.VIDEO,
                packet.frame().codecType(),
                packet.frame().sessionId(),
                packet.frame().streamKey(),
                packet.frame().trackId(),
                timestampMillis,
                timestampMillis,
                state.currentAccessUnitKeyFrame(),
                false,
                packet.frame().remoteAddress(),
                buildLengthPrefixedPayload(state.currentAccessUnitNals())
        ));
        state.clearAccessUnit();
        return frames;
    }

    private Long toTimestampMillis(RtpToRtmpTrackState state, int clockRate, long rtpTimestamp) {
        if (state.firstRtpTimestamp() == null) {
            state.firstRtpTimestamp(Long.valueOf(rtpTimestamp));
        }
        long delta = (rtpTimestamp - state.firstRtpTimestamp().longValue()) & 0xFFFFFFFFL;
        return Long.valueOf((delta * 1000L) / Math.max(clockRate, 1));
    }

    private RtpToRtmpTrackState state(String trackId) {
        RtpToRtmpTrackState existing = statesByTrackId.get(trackId);
        if (existing != null) {
            return existing;
        }
        RtpToRtmpTrackState created = new RtpToRtmpTrackState();
        RtpToRtmpTrackState previous = statesByTrackId.putIfAbsent(trackId, created);
        return previous == null ? created : previous;
    }

    private boolean cacheAndAppendH264StapA(RtpToRtmpTrackState state, byte[] payload, int offset, int length) {
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
                state.h264Sps(nal);
            } else if (nalType == H264_NAL_TYPE_PPS) {
                state.h264Pps(nal);
            } else {
                state.addNalToAccessUnit(nal, nalType == H264_NAL_TYPE_IDR);
                hasVideoNal = true;
            }
            cursor += nalLength;
        }
        return hasVideoNal;
    }

    private boolean cacheAndAppendH265Ap(RtpToRtmpTrackState state, byte[] payload, int offset, int length) {
        int cursor = offset;
        int limit = offset + length;
        boolean hasVideoNal = false;
        while (cursor + 2 <= limit) {
            int nalLength = ((payload[cursor] & 0xFF) << 8) | (payload[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalLength <= 0 || cursor + nalLength > limit) {
                return hasVideoNal;
            }
            int nalType = (payload[cursor] & 0x7E) >> 1;
            byte[] nal = copy(payload, cursor, nalLength);
            if (nalType == H265_NAL_TYPE_VPS) {
                state.h265Vps(nal);
            } else if (nalType == H265_NAL_TYPE_SPS) {
                state.h265Sps(nal);
            } else if (nalType == H265_NAL_TYPE_PPS) {
                state.h265Pps(nal);
            } else {
                state.addNalToAccessUnit(nal, isH265KeyFrameNalType(nalType));
                hasVideoNal = true;
            }
            cursor += nalLength;
        }
        return hasVideoNal;
    }

    private byte[] buildH264Config(RtpToRtmpTrackState state) {
        if (state.h264Sps() == null || state.h264Pps() == null || state.h264Sps().length < 4) {
            return null;
        }
        byte[] sps = state.h264Sps();
        byte[] pps = state.h264Pps();
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

    private byte[] buildH265Config(RtpToRtmpTrackState state) {
        if (state.h265Vps() == null || state.h265Sps() == null || state.h265Pps() == null) {
            return null;
        }
        byte[] vps = state.h265Vps();
        byte[] sps = state.h265Sps();
        byte[] pps = state.h265Pps();
        byte[] config = new byte[23 + (5 + vps.length) + (5 + sps.length) + (5 + pps.length)];
        config[0] = 0x01;
        config[21] = 0x03;
        config[22] = 0x03;
        int index = 23;
        index = appendHevcArray(config, index, H265_NAL_TYPE_VPS, vps);
        index = appendHevcArray(config, index, H265_NAL_TYPE_SPS, sps);
        appendHevcArray(config, index, H265_NAL_TYPE_PPS, pps);
        return config;
    }

    private int appendHevcArray(byte[] config, int index, int nalType, byte[] nal) {
        config[index++] = (byte) nalType;
        config[index++] = 0x00;
        config[index++] = 0x01;
        config[index++] = (byte) ((nal.length >> 8) & 0xFF);
        config[index++] = (byte) (nal.length & 0xFF);
        System.arraycopy(nal, 0, config, index, nal.length);
        return index + nal.length;
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

    private boolean isH265KeyFrameNalType(int nalType) {
        return nalType >= 16 && nalType <= 21;
    }

    private void primeH264ParameterSetsFromTrack(RtpToRtmpTrackState state, ITrack track) {
        if (track == null) {
            return;
        }
        if (state.h264Sps() == null && track.h264Sps() != null) {
            state.h264Sps(track.h264Sps());
        }
        if (state.h264Pps() == null && track.h264Pps() != null) {
            state.h264Pps(track.h264Pps());
        }
    }

    private void primeH265ParameterSetsFromTrack(RtpToRtmpTrackState state, ITrack track) {
        if (track == null) {
            return;
        }
        if (state.h265Vps() == null && track.h265Vps() != null) {
            state.h265Vps(track.h265Vps());
        }
        if (state.h265Sps() == null && track.h265Sps() != null) {
            state.h265Sps(track.h265Sps());
        }
        if (state.h265Pps() == null && track.h265Pps() != null) {
            state.h265Pps(track.h265Pps());
        }
    }

    private byte[] buildAacConfig(int sampleRate, int channels) {
        int sampleRateIndex = sampleRateIndex(sampleRate);
        int channelConfig = channels <= 0 ? 2 : channels;
        int audioObjectType = 2;
        int value = (audioObjectType << 11) | (sampleRateIndex << 7) | (channelConfig << 3);
        return new byte[]{
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private int sampleRateIndex(int sampleRate) {
        switch (sampleRate) {
            case 96000:
                return 0;
            case 88200:
                return 1;
            case 64000:
                return 2;
            case 48000:
                return 3;
            case 44100:
                return 4;
            case 32000:
                return 5;
            case 24000:
                return 6;
            case 22050:
                return 7;
            case 16000:
                return 8;
            case 12000:
                return 9;
            case 11025:
                return 10;
            case 8000:
                return 11;
            case 7350:
                return 12;
            default:
                return 3;
        }
    }

    private byte[] copy(byte[] payload, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(payload, offset, copy, 0, length);
        return copy;
    }
}
