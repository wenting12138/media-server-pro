package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
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

public final class RtspRtpAudioCanonicalizer implements AudioFrameCanonicalizer {

    private final RtpPacketParser parser = new RtpPacketParser();
    private final Map<String, Boolean> emittedConfigByTrackId = new ConcurrentHashMap<String, Boolean>();

    @Override
    public CanonicalAudioFrame canonicalize(InboundMediaFrame frame) {
        return null;
    }

    @Override
    public List<CanonicalAudioFrame> canonicalize(InboundRtpPacket packet, ITrack track) {
        if (packet == null || packet.frame() == null) {
            return Collections.emptyList();
        }
        InboundMediaFrame sourceFrame = packet.frame();
        if (sourceFrame.trackType() != TrackType.AUDIO) {
            return Collections.emptyList();
        }
        RtpParseResult parseResult = parser.parse(sourceFrame.payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null) {
            return Collections.emptyList();
        }
        byte[] rtpPayload = extractPayload(sourceFrame.payload(), header);
        if (rtpPayload.length == 0) {
            return Collections.emptyList();
        }
        CodecType codecType = sourceFrame.codecType();
        if (codecType == CodecType.G711A || codecType == CodecType.G711U || codecType == CodecType.OPUS) {
            return Collections.singletonList(new CanonicalAudioFrame(
                    cloneFrame(sourceFrame, codecType, false, rtpPayload),
                    codecType,
                    rtpPayload,
                    false
            ));
        }
        if (codecType != CodecType.AAC && codecType != CodecType.MPEG4_GENERIC) {
            return Collections.emptyList();
        }
        List<CanonicalAudioFrame> frames = new ArrayList<CanonicalAudioFrame>(2);
        byte[] config = track == null ? null : track.aacAudioSpecificConfig();
        String trackId = sourceFrame.trackId() == null ? "" : sourceFrame.trackId().trim();
        if (config != null && !Boolean.TRUE.equals(emittedConfigByTrackId.putIfAbsent(trackId, Boolean.TRUE))) {
            InboundMediaFrame configFrame = cloneFrame(sourceFrame, codecType, true, config);
            frames.add(new CanonicalAudioFrame(configFrame, codecType, config, true));
        }
        List<byte[]> accessUnits = extractAacAccessUnits(rtpPayload, track);
        if (accessUnits.isEmpty()) {
            return frames;
        }
        for (byte[] accessUnit : accessUnits) {
            InboundMediaFrame mediaFrame = cloneFrame(sourceFrame, codecType, false, accessUnit);
            frames.add(new CanonicalAudioFrame(mediaFrame, codecType, accessUnit, false));
        }
        return frames;
    }

    @Override
    public void close() {
        emittedConfigByTrackId.clear();
    }

    private byte[] extractPayload(byte[] packetBytes, RtpPacketHeader header) {
        if (packetBytes == null || header == null) {
            return new byte[0];
        }
        int offset = header.payloadOffset();
        int length = header.payloadLength();
        if (offset < 0 || length <= 0 || offset + length > packetBytes.length) {
            return new byte[0];
        }
        byte[] payload = new byte[length];
        System.arraycopy(packetBytes, offset, payload, 0, length);
        return payload;
    }

    private List<byte[]> extractAacAccessUnits(byte[] rtpPayload, ITrack track) {
        if (rtpPayload == null || rtpPayload.length < 4) {
            return Collections.emptyList();
        }
        int auHeadersLengthBits = ((rtpPayload[0] & 0xFF) << 8) | (rtpPayload[1] & 0xFF);
        if (auHeadersLengthBits <= 0) {
            return Collections.emptyList();
        }
        int auHeadersLengthBytes = (auHeadersLengthBits + 7) / 8;
        if (auHeadersLengthBytes < 2 || rtpPayload.length < 2 + auHeadersLengthBytes) {
            return Collections.emptyList();
        }
        int dataOffset = 2 + auHeadersLengthBytes;
        int remainingBytes = rtpPayload.length - dataOffset;
        if (remainingBytes <= 0) {
            return Collections.emptyList();
        }
        int sizeLength = track == null ? 13 : track.aacSizeLength();
        int indexLength = track == null ? 3 : track.aacIndexLength();
        int indexDeltaLength = track == null ? 3 : track.aacIndexDeltaLength();
        byte[] auHeaderBytes = new byte[auHeadersLengthBytes];
        System.arraycopy(rtpPayload, 2, auHeaderBytes, 0, auHeadersLengthBytes);
        List<Integer> auSizes = parseAuSizes(auHeaderBytes, auHeadersLengthBits, sizeLength, indexLength, indexDeltaLength);
        List<Integer> fallbackAuSizes = fallbackSingleAuSizes(auHeaderBytes, remainingBytes);
        if (shouldUseFallbackSizes(auSizes, fallbackAuSizes, remainingBytes)) {
            auSizes = fallbackAuSizes;
        }
        if (auSizes.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> accessUnits = new ArrayList<byte[]>(auSizes.size());
        int cursor = dataOffset;
        for (Integer auSizeBytes : auSizes) {
            if (auSizeBytes == null || auSizeBytes.intValue() <= 0) {
                continue;
            }
            int size = auSizeBytes.intValue();
            if (cursor + size > rtpPayload.length) {
                break;
            }
            byte[] accessUnit = new byte[size];
            System.arraycopy(rtpPayload, cursor, accessUnit, 0, size);
            accessUnits.add(accessUnit);
            cursor += size;
        }
        return accessUnits;
    }

    private boolean shouldUseFallbackSizes(List<Integer> auSizes, List<Integer> fallbackAuSizes, int remainingBytes) {
        if (fallbackAuSizes.isEmpty()) {
            return false;
        }
        if (auSizes == null || auSizes.isEmpty()) {
            return true;
        }
        int parsedTotal = 0;
        for (Integer size : auSizes) {
            if (size != null && size.intValue() > 0) {
                parsedTotal += size.intValue();
            }
        }
        int fallbackTotal = 0;
        for (Integer size : fallbackAuSizes) {
            if (size != null && size.intValue() > 0) {
                fallbackTotal += size.intValue();
            }
        }
        return fallbackTotal == remainingBytes && parsedTotal != remainingBytes;
    }

    private List<Integer> fallbackSingleAuSizes(byte[] auHeaderBytes, int remainingBytes) {
        if (auHeaderBytes == null || auHeaderBytes.length < 2 || remainingBytes <= 0) {
            return Collections.emptyList();
        }
        int rawHeaderValue = ((auHeaderBytes[0] & 0xFF) << 8) | (auHeaderBytes[1] & 0xFF);
        int auSizeBytes = (rawHeaderValue + 7) / 8;
        if (auSizeBytes <= 0 || auSizeBytes != remainingBytes) {
            if (remainingBytes > 0) {
                return Collections.singletonList(Integer.valueOf(remainingBytes));
            }
            return Collections.emptyList();
        }
        return Collections.singletonList(Integer.valueOf(auSizeBytes));
    }

    private List<Integer> parseAuSizes(byte[] auHeaderBytes, int auHeadersLengthBits, int sizeLength, int indexLength, int indexDeltaLength) {
        if (auHeaderBytes == null || auHeaderBytes.length == 0 || auHeadersLengthBits <= 0 || sizeLength <= 0) {
            return Collections.emptyList();
        }
        int bitOffset = 0;
        int headerIndexBits = indexLength;
        List<Integer> sizes = new ArrayList<Integer>(4);
        while (bitOffset + sizeLength + headerIndexBits <= auHeadersLengthBits) {
            int auSizeBits = readBits(auHeaderBytes, bitOffset, sizeLength);
            bitOffset += sizeLength;
            bitOffset += headerIndexBits;
            int auSizeBytes = (auSizeBits + 7) / 8;
            if (auSizeBytes <= 0) {
                break;
            }
            sizes.add(Integer.valueOf(auSizeBytes));
            headerIndexBits = indexDeltaLength;
        }
        return sizes;
    }

    private int readBits(byte[] bytes, int bitOffset, int bitLength) {
        int value = 0;
        for (int i = 0; i < bitLength; i++) {
            int absoluteBit = bitOffset + i;
            int byteIndex = absoluteBit / 8;
            int bitIndex = 7 - (absoluteBit % 8);
            if (byteIndex < 0 || byteIndex >= bytes.length) {
                return value;
            }
            value = (value << 1) | ((bytes[byteIndex] >> bitIndex) & 0x01);
        }
        return value;
    }

    private InboundMediaFrame cloneFrame(InboundMediaFrame sourceFrame, CodecType codecType, boolean configFrame, byte[] payload) {
        return new InboundMediaFrame(
                sourceFrame.sourceProtocol(),
                TrackType.AUDIO,
                codecType,
                sourceFrame.sessionId(),
                sourceFrame.streamKey(),
                sourceFrame.trackId(),
                sourceFrame.ptsMillis(),
                sourceFrame.dtsMillis(),
                false,
                configFrame,
                sourceFrame.outOfBandParameterSetsReady(),
                sourceFrame.remoteAddress(),
                payload
        );
    }
}
