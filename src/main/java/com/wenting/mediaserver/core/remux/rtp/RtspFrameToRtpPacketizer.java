package com.wenting.mediaserver.core.remux.rtp;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.track.ITrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtspFrameToRtpPacketizer {

    private static final int MAX_RTP_PAYLOAD_SIZE = 1200;

    private final AvcDecoderConfigurationRecordParser avcConfigParser = new AvcDecoderConfigurationRecordParser();
    private final HevcDecoderConfigurationRecordParser hevcConfigParser = new HevcDecoderConfigurationRecordParser();

    public List<RtpPacketChunk> packetize(InboundMediaFrame frame, ITrack track, InboundMediaFrame latestConfigFrame) {
        if (frame == null) {
            return Collections.emptyList();
        }
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H264) {
            return packetizeH264(frame, latestConfigFrame);
        }
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H265) {
            return packetizeH265(frame, latestConfigFrame);
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.AAC || frame.codecType() == CodecType.MPEG4_GENERIC)) {
            return packetizeAac(frame);
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.G711A || frame.codecType() == CodecType.G711U)) {
            return Collections.singletonList(new RtpPacketChunk(frame.payload(), true));
        }
        return Collections.emptyList();
    }

    private List<RtpPacketChunk> packetizeH264(InboundMediaFrame frame, InboundMediaFrame latestConfigFrame) {
        AvcDecoderConfigurationRecord config = parseAvcConfig(frame, latestConfigFrame);
        if (config == null) {
            return Collections.emptyList();
        }
        if (frame.configFrame()) {
            return packetizeParameterSets(config.spsList(), config.ppsList());
        }
        return packetizeH264AccessUnit(frame.payload(), config.nalLengthSize());
    }

    private List<RtpPacketChunk> packetizeH265(InboundMediaFrame frame, InboundMediaFrame latestConfigFrame) {
        HevcDecoderConfigurationRecord config = parseHevcConfig(frame, latestConfigFrame);
        if (config == null) {
            return Collections.emptyList();
        }
        if (frame.configFrame()) {
            List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
            appendNalChunks(chunks, config.vpsList(), false);
            appendNalChunks(chunks, config.spsList(), false);
            appendNalChunks(chunks, config.ppsList(), true);
            return chunks;
        }
        return packetizeH265AccessUnit(frame.payload(), config.nalLengthSize());
    }

    private List<RtpPacketChunk> packetizeAac(InboundMediaFrame frame) {
        if (frame.configFrame()) {
            return Collections.emptyList();
        }
        byte[] audioPayload = frame.payload();
        byte[] payload = new byte[4 + audioPayload.length];
        payload[0] = 0x00;
        payload[1] = 0x10;
        int sizeBits = audioPayload.length << 3;
        payload[2] = (byte) ((sizeBits >> 8) & 0xFF);
        payload[3] = (byte) (sizeBits & 0xFF);
        System.arraycopy(audioPayload, 0, payload, 4, audioPayload.length);
        return Collections.singletonList(new RtpPacketChunk(payload, true));
    }

    private AvcDecoderConfigurationRecord parseAvcConfig(InboundMediaFrame frame, InboundMediaFrame latestConfigFrame) {
        InboundMediaFrame source = frame.configFrame() ? frame : latestConfigFrame;
        return source == null ? null : avcConfigParser.parse(source.payload());
    }

    private HevcDecoderConfigurationRecord parseHevcConfig(InboundMediaFrame frame, InboundMediaFrame latestConfigFrame) {
        InboundMediaFrame source = frame.configFrame() ? frame : latestConfigFrame;
        return source == null ? null : hevcConfigParser.parse(source.payload());
    }

    private List<RtpPacketChunk> packetizeParameterSets(List<byte[]> spsList, List<byte[]> ppsList) {
        List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
        appendNalChunks(chunks, spsList, false);
        appendNalChunks(chunks, ppsList, true);
        return chunks;
    }

    private void appendNalChunks(List<RtpPacketChunk> target, List<byte[]> nals, boolean markerForLast) {
        for (int i = 0; i < nals.size(); i++) {
            boolean marker = markerForLast && i == nals.size() - 1;
            target.addAll(packetizeSingleNal(nals.get(i), marker));
        }
    }

    private List<RtpPacketChunk> packetizeH264AccessUnit(byte[] payload, int nalLengthSize) {
        List<byte[]> nals = splitLengthPrefixedNals(payload, nalLengthSize);
        List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
        for (int i = 0; i < nals.size(); i++) {
            chunks.addAll(packetizeSingleNal(nals.get(i), i == nals.size() - 1));
        }
        return chunks;
    }

    private List<RtpPacketChunk> packetizeH265AccessUnit(byte[] payload, int nalLengthSize) {
        List<byte[]> nals = splitLengthPrefixedNals(payload, nalLengthSize);
        List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
        for (int i = 0; i < nals.size(); i++) {
            byte[] nal = nals.get(i);
            boolean marker = i == nals.size() - 1;
            if (nal.length <= MAX_RTP_PAYLOAD_SIZE) {
                chunks.add(new RtpPacketChunk(nal, marker));
                continue;
            }
            chunks.addAll(fragmentH265Nal(nal, marker));
        }
        return chunks;
    }

    private List<RtpPacketChunk> packetizeSingleNal(byte[] nal, boolean marker) {
        if (nal == null || nal.length == 0) {
            return Collections.emptyList();
        }
        if (nal.length <= MAX_RTP_PAYLOAD_SIZE) {
            return Collections.singletonList(new RtpPacketChunk(nal, marker));
        }
        return fragmentH264Nal(nal, marker);
    }

    private List<byte[]> splitLengthPrefixedNals(byte[] payload, int nalLengthSize) {
        if (payload == null || payload.length == 0) {
            return Collections.emptyList();
        }
        int prefix = nalLengthSize <= 0 ? 4 : nalLengthSize;
        List<byte[]> nals = new ArrayList<byte[]>();
        int index = 0;
        while (index + prefix <= payload.length) {
            int nalLength = 0;
            for (int i = 0; i < prefix; i++) {
                nalLength = (nalLength << 8) | (payload[index + i] & 0xFF);
            }
            index += prefix;
            if (nalLength <= 0 || index + nalLength > payload.length) {
                break;
            }
            byte[] nal = new byte[nalLength];
            System.arraycopy(payload, index, nal, 0, nalLength);
            nals.add(nal);
            index += nalLength;
        }
        return nals;
    }

    private List<RtpPacketChunk> fragmentH264Nal(byte[] nal, boolean markerForLast) {
        List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
        int nalType = nal[0] & 0x1F;
        int nri = nal[0] & 0x60;
        int offset = 1;
        while (offset < nal.length) {
            int remaining = nal.length - offset;
            int chunkSize = Math.min(remaining, MAX_RTP_PAYLOAD_SIZE - 2);
            byte[] payload = new byte[2 + chunkSize];
            payload[0] = (byte) (nri | 28);
            payload[1] = (byte) nalType;
            if (offset == 1) {
                payload[1] |= (byte) 0x80;
            }
            if (offset + chunkSize >= nal.length) {
                payload[1] |= 0x40;
            }
            System.arraycopy(nal, offset, payload, 2, chunkSize);
            boolean marker = markerForLast && offset + chunkSize >= nal.length;
            chunks.add(new RtpPacketChunk(payload, marker));
            offset += chunkSize;
        }
        return chunks;
    }

    private List<RtpPacketChunk> fragmentH265Nal(byte[] nal, boolean markerForLast) {
        List<RtpPacketChunk> chunks = new ArrayList<RtpPacketChunk>();
        if (nal.length < 3) {
            return Collections.emptyList();
        }
        int nalType = (nal[0] & 0x7E) >> 1;
        byte fuIndicator0 = (byte) ((nal[0] & 0x81) | (49 << 1));
        byte fuIndicator1 = nal[1];
        int offset = 2;
        while (offset < nal.length) {
            int remaining = nal.length - offset;
            int chunkSize = Math.min(remaining, MAX_RTP_PAYLOAD_SIZE - 3);
            byte[] payload = new byte[3 + chunkSize];
            payload[0] = fuIndicator0;
            payload[1] = fuIndicator1;
            payload[2] = (byte) (nalType & 0x3F);
            if (offset == 2) {
                payload[2] |= (byte) 0x80;
            }
            if (offset + chunkSize >= nal.length) {
                payload[2] |= 0x40;
            }
            System.arraycopy(nal, offset, payload, 3, chunkSize);
            boolean marker = markerForLast && offset + chunkSize >= nal.length;
            chunks.add(new RtpPacketChunk(payload, marker));
            offset += chunkSize;
        }
        return chunks;
    }
}
