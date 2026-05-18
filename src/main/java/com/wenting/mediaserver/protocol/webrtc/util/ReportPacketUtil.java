package com.wenting.mediaserver.protocol.webrtc.util;

import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;

import java.util.Collections;
import java.util.List;

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


}
