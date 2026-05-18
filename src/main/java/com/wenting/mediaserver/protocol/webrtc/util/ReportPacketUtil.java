package com.wenting.mediaserver.protocol.webrtc.util;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ReportPacketUtil {

    public static byte[] encodeReceiverReportPacket(long senderSsrc, List<RtcpReportBlock> reportBlocks) {
        List<RtcpReportBlock> safeBlocks = reportBlocks == null
                ? Collections.<RtcpReportBlock>emptyList()
                : reportBlocks;
        int reportCount = Math.min(31, safeBlocks.size());
        int totalLength = 8 + (reportCount * 24);
        byte[] packet = new byte[totalLength];
        packet[0] = (byte) (0x80 | (reportCount & 0x1F));
        packet[1] = (byte) 201;
        int lengthWords = (totalLength / 4) - 1;
        writeUnsignedShort(packet, 2, lengthWords);
        writeUnsignedInt(packet, 4, senderSsrc & 0xFFFFFFFFL);
        int offset = 8;
        for (int i = 0; i < reportCount; i++) {
            RtcpReportBlock block = safeBlocks.get(i);
            writeUnsignedInt(packet, offset, block.ssrc() & 0xFFFFFFFFL);
            packet[offset + 4] = (byte) (block.fractionLost() & 0xFF);
            writeSigned24(packet, offset + 5, clampSigned24(block.cumulativeLost()));
            writeUnsignedInt(packet, offset + 8, block.extendedHighestSequenceNumber() & 0xFFFFFFFFL);
            writeUnsignedInt(packet, offset + 12, block.interarrivalJitter() & 0xFFFFFFFFL);
            writeUnsignedInt(packet, offset + 16, block.lastSenderReport() & 0xFFFFFFFFL);
            writeUnsignedInt(packet, offset + 20, block.delaySinceLastSenderReport() & 0xFFFFFFFFL);
            offset += 24;
        }
        return packet;
    }

    public static byte[] encodeGenericNackPacket(long senderSsrc, long mediaSsrc, List<Integer> lostSequenceNumbers) {
        List<NackChunk> chunks = buildNackChunks(lostSequenceNumbers);
        int totalLength = 12 + (chunks.size() * 4);
        byte[] packet = new byte[totalLength];
        packet[0] = (byte) 0x81;
        packet[1] = (byte) 205;
        int lengthWords = (totalLength / 4) - 1;
        writeUnsignedShort(packet, 2, lengthWords);
        writeUnsignedInt(packet, 4, senderSsrc & 0xFFFFFFFFL);
        writeUnsignedInt(packet, 8, mediaSsrc & 0xFFFFFFFFL);
        int offset = 12;
        for (NackChunk chunk : chunks) {
            writeUnsignedShort(packet, offset, chunk.pid);
            writeUnsignedShort(packet, offset + 2, chunk.blp);
            offset += 4;
        }
        return packet;
    }

    private static List<NackChunk> buildNackChunks(List<Integer> lostSequenceNumbers) {
        if (lostSequenceNumbers == null || lostSequenceNumbers.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Integer> ordered = new LinkedHashSet<Integer>();
        for (Integer sequenceNumber : lostSequenceNumbers) {
            if (sequenceNumber != null) {
                ordered.add(Integer.valueOf(sequenceNumber.intValue() & 0xFFFF));
            }
        }
        List<NackChunk> chunks = new ArrayList<NackChunk>();
        List<Integer> sequenceNumbers = new ArrayList<Integer>(ordered);
        int index = 0;
        while (index < sequenceNumbers.size()) {
            int pid = sequenceNumbers.get(index).intValue() & 0xFFFF;
            int blp = 0;
            index++;
            while (index < sequenceNumbers.size()) {
                int sequenceNumber = sequenceNumbers.get(index).intValue() & 0xFFFF;
                int delta = (sequenceNumber - pid) & 0xFFFF;
                if (delta <= 0 || delta > 16) {
                    break;
                }
                blp |= (1 << (delta - 1));
                index++;
            }
            chunks.add(new NackChunk(pid, blp));
        }
        return chunks;
    }

    private static int clampSigned24(int value) {
        if (value > 0x7FFFFF) {
            return 0x7FFFFF;
        }
        if (value < -0x800000) {
            return -0x800000;
        }
        return value;
    }

    private static void writeUnsignedShort(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeUnsignedInt(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeSigned24(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) (value & 0xFF);
    }

    private static final class NackChunk {
        private final int pid;
        private final int blp;

        private NackChunk(int pid, int blp) {
            this.pid = pid;
            this.blp = blp;
        }
    }

}
