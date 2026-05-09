package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.remux.rtp.AacAudioSpecificConfig;
import com.wenting.mediaserver.core.remux.rtp.AvcDecoderConfigurationRecord;
import com.wenting.mediaserver.core.remux.rtp.AvcDecoderConfigurationRecordParser;
import com.wenting.mediaserver.core.remux.rtp.HevcDecoderConfigurationRecord;
import com.wenting.mediaserver.core.remux.rtp.HevcDecoderConfigurationRecordParser;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.enums.publish.CodecType;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class TsMuxer {

    private static final int TS_PACKET_SIZE = 188;
    private static final int PID_PAT = 0x0000;
    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x0100;
    private static final int PID_AUDIO = 0x0101;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final AvcDecoderConfigurationRecordParser avcConfigParser = new AvcDecoderConfigurationRecordParser();
    private final HevcDecoderConfigurationRecordParser hevcConfigParser = new HevcDecoderConfigurationRecordParser();
    private byte[] h264Sps;
    private byte[] h264Pps;
    private int h264NalLengthSize = 4;
    private byte[] h265Vps;
    private byte[] h265Sps;
    private byte[] h265Pps;
    private int h265NalLengthSize = 4;
    private AacAudioSpecificConfig aacConfig;
    private CodecType videoCodecType = CodecType.UNKNOWN;
    private int continuityPat;
    private int continuityPmt;
    private int continuityVideo;
    private int continuityAudio;
    private boolean tablesWritten;

    void onFrame(InboundMediaFrame frame) {
        if (frame == null) {
            return;
        }
        if (frame.configFrame()) {
            onConfigFrame(frame);
            return;
        }
        ensureTables();
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.VIDEO) {
            if (frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.H264){
                writeVideoFrame(frame);
                return;
            }
            if (frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.H265)) {
                writeVideoFrame(frame);
                return;
            }
        }
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.AUDIO) {
            if (frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.AAC
                    || frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.MPEG4_GENERIC
            ) {
                writeAacFrame(frame);
            }
        }
    }

    byte[] bytes() {
        return out.toByteArray();
    }

    void reset() {
        out.reset();
        tablesWritten = false;
        continuityPat = 0;
        continuityPmt = 0;
        continuityVideo = 0;
        continuityAudio = 0;
    }

    private void onConfigFrame(InboundMediaFrame frame) {
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.VIDEO
                && frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.H264) {
            videoCodecType = CodecType.H264;
            AvcDecoderConfigurationRecord record = avcConfigParser.parse(frame.payload());
            if (record != null) {
                h264NalLengthSize = Math.max(record.nalLengthSize(), 1);
                if (!record.spsList().isEmpty()) {
                    h264Sps = record.spsList().get(0);
                }
                if (!record.ppsList().isEmpty()) {
                    h264Pps = record.ppsList().get(0);
                }
            }
            return;
        }
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.VIDEO
                && frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.H265) {
            videoCodecType = CodecType.H265;
            HevcDecoderConfigurationRecord record = hevcConfigParser.parse(frame.payload());
            if (record != null) {
                h265NalLengthSize = Math.max(record.nalLengthSize(), 1);
                if (!record.vpsList().isEmpty()) {
                    h265Vps = record.vpsList().get(0);
                }
                if (!record.spsList().isEmpty()) {
                    h265Sps = record.spsList().get(0);
                }
                if (!record.ppsList().isEmpty()) {
                    h265Pps = record.ppsList().get(0);
                }
            }
            return;
        }
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.AUDIO
                && (frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.AAC
                || frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.MPEG4_GENERIC)) {
            aacConfig = new com.wenting.mediaserver.core.remux.rtp.AacAudioSpecificConfigParser().parse(frame.payload());
        }
    }

    private void ensureTables() {
        if (tablesWritten) {
            return;
        }
        writePat();
        writePmt();
        tablesWritten = true;
    }

    private void writeVideoFrame(InboundMediaFrame frame) {
        if (frame.codecType() == CodecType.H264) {
            videoCodecType = CodecType.H264;
        } else if (frame.codecType() == CodecType.H265) {
            videoCodecType = CodecType.H265;
        }
        byte[] annexB = frame.codecType() == CodecType.H265
                ? hvccToAnnexB(frame.payload(), frame.keyFrame())
                : avccToAnnexB(frame.payload(), frame.keyFrame());
        if (annexB.length == 0) {
            return;
        }
        writePes(PID_VIDEO, 0xE0, to90k(frame.ptsMillis()), to90k(frame.dtsMillis()), annexB, true);
    }

    private void writeAacFrame(InboundMediaFrame frame) {
        if (aacConfig == null) {
            return;
        }
        byte[] adtsFrame = buildAdtsFrame(frame.payload(), aacConfig);
        writePes(PID_AUDIO, 0xC0, to90k(frame.ptsMillis()), null, adtsFrame, false);
    }

    private byte[] avccToAnnexB(byte[] payload, boolean keyFrame) {
        if (payload == null || payload.length < h264NalLengthSize) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (keyFrame) {
            if (h264Sps != null) {
                writeStartCodeNal(buffer, h264Sps);
            }
            if (h264Pps != null) {
                writeStartCodeNal(buffer, h264Pps);
            }
        }
        int offset = 0;
        while (offset + h264NalLengthSize <= payload.length) {
            int nalLength = readLength(payload, offset, h264NalLengthSize);
            offset += h264NalLengthSize;
            if (nalLength <= 0 || offset + nalLength > payload.length) {
                break;
            }
            writeStartCodeNal(buffer, copy(payload, offset, nalLength));
            offset += nalLength;
        }
        return buffer.toByteArray();
    }

    private byte[] buildAdtsFrame(byte[] rawAac, AacAudioSpecificConfig config) {
        int sampleRateIndex = sampleRateIndex(config.sampleRate());
        int channelConfig = Math.max(config.channels(), 1);
        int frameLength = rawAac.length + 7;
        byte[] adts = new byte[frameLength];
        adts[0] = (byte) 0xFF;
        adts[1] = (byte) 0xF1;
        adts[2] = (byte) (((2 - 1) << 6) | (sampleRateIndex << 2) | ((channelConfig >> 2) & 0x01));
        adts[3] = (byte) (((channelConfig & 0x03) << 6) | ((frameLength >> 11) & 0x03));
        adts[4] = (byte) ((frameLength >> 3) & 0xFF);
        adts[5] = (byte) (((frameLength & 0x07) << 5) | 0x1F);
        adts[6] = (byte) 0xFC;
        System.arraycopy(rawAac, 0, adts, 7, rawAac.length);
        return adts;
    }

    private void writePes(int pid, int streamId, long pts90k, Long dts90k, byte[] esPayload, boolean withPcr) {
        byte[] pesHeader = buildPesHeader(streamId, pts90k, dts90k);
        byte[] pes = new byte[pesHeader.length + esPayload.length];
        System.arraycopy(pesHeader, 0, pes, 0, pesHeader.length);
        System.arraycopy(esPayload, 0, pes, pesHeader.length, esPayload.length);
        int offset = 0;
        boolean first = true;
        while (offset < pes.length) {
            int continuity = pid == PID_VIDEO ? continuityVideo : continuityAudio;
            byte[] packet = new byte[TS_PACKET_SIZE];
            packet[0] = 0x47;
            packet[1] = (byte) (((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)) & 0xFF);
            packet[2] = (byte) (pid & 0xFF);
            int payloadCapacity = TS_PACKET_SIZE - 4;
            int adaptationControl = 1;
            int payloadOffset = 4;
            if (first && withPcr) {
                adaptationControl = 3;
                int adaptationLength = 7;
                packet[4] = (byte) adaptationLength;
                packet[5] = 0x10;
                writePcr(packet, 6, pts90k);
                payloadOffset = 12;
                payloadCapacity = TS_PACKET_SIZE - payloadOffset;
            }
            int remaining = pes.length - offset;
            int payloadSize = Math.min(payloadCapacity, remaining);
            if (payloadSize < payloadCapacity) {
                int stuffing = payloadCapacity - payloadSize;
                if (adaptationControl == 1) {
                    adaptationControl = 3;
                    int adaptationLength = stuffing - 1;
                    packet[4] = (byte) adaptationLength;
                    if (adaptationLength > 0) {
                        packet[5] = 0x00;
                        for (int i = 6; i < 5 + adaptationLength; i++) {
                            packet[i] = (byte) 0xFF;
                        }
                    }
                    payloadOffset = 5 + adaptationLength;
                } else {
                    int adaptationLength = (packet[4] & 0xFF) + stuffing;
                    packet[4] = (byte) adaptationLength;
                    for (int i = 12; i < payloadOffset + stuffing; i++) {
                        packet[i] = (byte) 0xFF;
                    }
                    payloadOffset += stuffing;
                }
                payloadSize = remaining;
            }
            packet[3] = (byte) (((adaptationControl & 0x03) << 4) | (continuity & 0x0F));
            System.arraycopy(pes, offset, packet, payloadOffset, payloadSize);
            offset += payloadSize;
            out.write(packet, 0, packet.length);
            if (pid == PID_VIDEO) {
                continuityVideo = (continuityVideo + 1) & 0x0F;
            } else {
                continuityAudio = (continuityAudio + 1) & 0x0F;
            }
            first = false;
        }
    }

    private byte[] buildPesHeader(int streamId, long pts90k, Long dts90k) {
        boolean ptsDts = dts90k != null && dts90k.longValue() != pts90k;
        int headerDataLength = ptsDts ? 10 : 5;
        byte[] header = new byte[9 + headerDataLength];
        header[0] = 0x00;
        header[1] = 0x00;
        header[2] = 0x01;
        header[3] = (byte) streamId;
        header[4] = 0x00;
        header[5] = 0x00;
        header[6] = (byte) 0x80;
        header[7] = (byte) (ptsDts ? 0xC0 : 0x80);
        header[8] = (byte) headerDataLength;
        writePts(header, 9, ptsDts ? 0x03 : 0x02, pts90k);
        if (ptsDts) {
            writePts(header, 14, 0x01, dts90k.longValue());
        }
        return header;
    }

    private void writePat() {
        byte[] section = new byte[16];
        section[0] = 0x00;
        section[1] = (byte) 0xB0;
        section[2] = 0x0D;
        section[3] = 0x00;
        section[4] = 0x01;
        section[5] = (byte) 0xC1;
        section[6] = 0x00;
        section[7] = 0x00;
        section[8] = 0x00;
        section[9] = 0x01;
        section[10] = (byte) 0xF0;
        section[11] = 0x00;
        int crc = crc32Mpeg(section, 0, 12);
        section[12] = (byte) ((crc >> 24) & 0xFF);
        section[13] = (byte) ((crc >> 16) & 0xFF);
        section[14] = (byte) ((crc >> 8) & 0xFF);
        section[15] = (byte) (crc & 0xFF);
        writePsiPacket(PID_PAT, section, true, true);
    }

    private void writePmt() {
        byte[] section = new byte[26];
        section[0] = 0x02;
        section[1] = (byte) 0xB0;
        section[2] = 0x17;
        section[3] = 0x00;
        section[4] = 0x01;
        section[5] = (byte) 0xC1;
        section[6] = 0x00;
        section[7] = 0x00;
        section[8] = (byte) ((PID_VIDEO >> 8) | 0xE0);
        section[9] = (byte) (PID_VIDEO & 0xFF);
        section[10] = (byte) 0xF0;
        section[11] = 0x00;
        section[12] = (byte) (videoCodecType == CodecType.H265 ? 0x24 : 0x1B);
        section[13] = (byte) ((PID_VIDEO >> 8) | 0xE0);
        section[14] = (byte) (PID_VIDEO & 0xFF);
        section[15] = (byte) 0xF0;
        section[16] = 0x00;
        section[17] = 0x0F;
        section[18] = (byte) ((PID_AUDIO >> 8) | 0xE0);
        section[19] = (byte) (PID_AUDIO & 0xFF);
        section[20] = (byte) 0xF0;
        section[21] = 0x00;
        int crc = crc32Mpeg(section, 0, 22);
        section[22] = (byte) ((crc >> 24) & 0xFF);
        section[23] = (byte) ((crc >> 16) & 0xFF);
        section[24] = (byte) ((crc >> 8) & 0xFF);
        section[25] = (byte) (crc & 0xFF);
        writePsiPacket(PID_PMT, section, false, true);
    }

    private void writePsiPacket(int pid, byte[] section, boolean pat, boolean payloadUnitStart) {
        byte[] packet = new byte[TS_PACKET_SIZE];
        packet[0] = 0x47;
        packet[1] = (byte) (((payloadUnitStart ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)) & 0xFF);
        packet[2] = (byte) (pid & 0xFF);
        int continuity = pat ? continuityPat : continuityPmt;
        packet[3] = (byte) (0x10 | (continuity & 0x0F));
        int offset = 4;
        packet[offset++] = 0x00;
        System.arraycopy(section, 0, packet, offset, section.length);
        offset += section.length;
        while (offset < packet.length) {
            packet[offset++] = (byte) 0xFF;
        }
        out.write(packet, 0, packet.length);
        if (pat) {
            continuityPat = (continuityPat + 1) & 0x0F;
        } else {
            continuityPmt = (continuityPmt + 1) & 0x0F;
        }
    }

    private static void writePts(byte[] target, int offset, int prefix, long pts) {
        long value = pts & 0x1FFFFFFFFL;
        target[offset] = (byte) (((prefix & 0x0F) << 4) | (((value >> 30) & 0x07) << 1) | 0x01);
        target[offset + 1] = (byte) ((value >> 22) & 0xFF);
        target[offset + 2] = (byte) ((((value >> 15) & 0x7F) << 1) | 0x01);
        target[offset + 3] = (byte) ((value >> 7) & 0xFF);
        target[offset + 4] = (byte) (((value & 0x7F) << 1) | 0x01);
    }

    private static void writePcr(byte[] packet, int offset, long pcrBase) {
        long base = pcrBase & 0x1FFFFFFFFL;
        packet[offset] = (byte) (base >> 25);
        packet[offset + 1] = (byte) (base >> 17);
        packet[offset + 2] = (byte) (base >> 9);
        packet[offset + 3] = (byte) (base >> 1);
        packet[offset + 4] = (byte) (((base & 0x01) << 7) | 0x7E);
        packet[offset + 5] = 0x00;
    }

    private static long to90k(Long millis) {
        if (millis == null || millis.longValue() < 0L) {
            return 0L;
        }
        return millis.longValue() * 90L;
    }

    private static int crc32Mpeg(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80000000) != 0) {
                    crc = (crc << 1) ^ 0x04C11DB7;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }

    private static void writeStartCodeNal(ByteArrayOutputStream buffer, byte[] nal) {
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x00);
        buffer.write(0x01);
        buffer.write(nal, 0, nal.length);
    }

    private static int readLength(byte[] payload, int offset, int lengthSize) {
        int value = 0;
        for (int i = 0; i < lengthSize; i++) {
            value = (value << 8) | (payload[offset + i] & 0xFF);
        }
        return value;
    }

    private static byte[] copy(byte[] payload, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(payload, offset, copy, 0, length);
        return copy;
    }

    private static int sampleRateIndex(int sampleRate) {
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

    private byte[] hvccToAnnexB(byte[] payload, boolean keyFrame) {
        if (payload == null || payload.length < h265NalLengthSize) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (keyFrame) {
            if (h265Vps != null) {
                writeStartCodeNal(buffer, h265Vps);
            }
            if (h265Sps != null) {
                writeStartCodeNal(buffer, h265Sps);
            }
            if (h265Pps != null) {
                writeStartCodeNal(buffer, h265Pps);
            }
        }
        int offset = 0;
        while (offset + h265NalLengthSize <= payload.length) {
            int nalLength = readLength(payload, offset, h265NalLengthSize);
            offset += h265NalLengthSize;
            if (nalLength <= 0 || offset + nalLength > payload.length) {
                break;
            }
            writeStartCodeNal(buffer, copy(payload, offset, nalLength));
            offset += nalLength;
        }
        return buffer.toByteArray();
    }
}
