package com.wenting.mediaserver.core.codec.rtp;

import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpGenericNackPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPacketHeader;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPictureLossIndicationPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReceiverReportPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses raw RTP or RTCP packet bytes.
 */
public final class RtpPacketParser {

    private static final int RTP_FIXED_HEADER_LENGTH = 12;
    private static final int RTCP_COMMON_HEADER_LENGTH = 4;
    private static final int RTP_VERSION = 2;

    public RtpParseResult parse(byte[] packet) {
        if (packet == null || packet.length < RTCP_COMMON_HEADER_LENGTH) {
            return null;
        }
        int version = (packet[0] >> 6) & 0x03;
        if (version != RTP_VERSION) {
            return null;
        }
        int secondByte = unsignedByte(packet[1]);
        if (looksLikeRtcp(secondByte, packet.length)) {
            return parseRtcp(packet, version);
        }
        return parseRtp(packet, version);
    }

    private RtpParseResult parseRtp(byte[] packet, int version) {
        if (packet.length < RTP_FIXED_HEADER_LENGTH) {
            return null;
        }
        boolean padding = (packet[0] & 0x20) != 0;
        boolean extension = (packet[0] & 0x10) != 0;
        int csrcCount = packet[0] & 0x0F;
        boolean marker = (packet[1] & 0x80) != 0;
        int payloadType = packet[1] & 0x7F;
        int sequenceNumber = unsignedShort(packet, 2);
        long timestamp = unsignedInt(packet, 4);
        long ssrc = unsignedInt(packet, 8);

        int headerLength = RTP_FIXED_HEADER_LENGTH + (csrcCount * 4);
        if (packet.length < headerLength) {
            return null;
        }
        if (extension) {
            if (packet.length < headerLength + 4) {
                return null;
            }
            int extensionWords = unsignedShort(packet, headerLength + 2);
            headerLength += 4 + (extensionWords * 4);
            if (packet.length < headerLength) {
                return null;
            }
        }

        int paddingLength = 0;
        if (padding) {
            paddingLength = unsignedByte(packet[packet.length - 1]);
            if (paddingLength <= 0 || headerLength + paddingLength > packet.length) {
                return null;
            }
        }

        int payloadLength = packet.length - headerLength - paddingLength;
        if (payloadLength < 0) {
            return null;
        }
        return RtpParseResult.rtp(new RtpPacketHeader(
                version,
                padding,
                extension,
                csrcCount,
                marker,
                payloadType,
                sequenceNumber,
                timestamp,
                ssrc,
                headerLength,
                headerLength,
                payloadLength
        ));
    }

    private RtpParseResult parseRtcp(byte[] packet, int version) {
        boolean padding = (packet[0] & 0x20) != 0;
        int reportCount = packet[0] & 0x1F;
        int packetType = unsignedByte(packet[1]);
        int length = unsignedShort(packet, 2);
        int packetLength = (length + 1) * 4;
        if (packet.length < packetLength) {
            return null;
        }
        long senderSsrc = packetLength >= 8 ? unsignedInt(packet, 4) : 0L;
        RtcpPacketHeader header = new RtcpPacketHeader(
                version,
                padding,
                reportCount,
                packetType,
                length,
                packetLength,
                senderSsrc
        );
        return RtpParseResult.rtcp(header, parseRtcpPacket(packet, header));
    }

    private boolean looksLikeRtcp(int secondByte, int packetLength) {
        return packetLength >= 8 && secondByte >= 192 && secondByte <= 223;
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private static int unsignedShort(byte[] packet, int offset) {
        return (unsignedByte(packet[offset]) << 8) | unsignedByte(packet[offset + 1]);
    }

    private static long unsignedInt(byte[] packet, int offset) {
        return ((long) unsignedByte(packet[offset]) << 24)
                | ((long) unsignedByte(packet[offset + 1]) << 16)
                | ((long) unsignedByte(packet[offset + 2]) << 8)
                | unsignedByte(packet[offset + 3]);
    }

    private RtcpPacket parseRtcpPacket(byte[] packet, RtcpPacketHeader header) {
        if (packet == null || header == null) {
            return null;
        }
        if (header.packetType() == 200) {
            return parseSenderReport(packet, header);
        }
        if (header.packetType() == 201) {
            return parseReceiverReport(packet, header);
        }
        if (header.packetType() == 205 && header.reportCount() == 1) {
            return parseGenericNack(packet);
        }
        if (header.packetType() == 206 && header.reportCount() == 1) {
            return parsePictureLossIndication(packet);
        }
        return null;
    }

    private RtcpSenderReportPacket parseSenderReport(byte[] packet, RtcpPacketHeader header) {
        if (packet.length < 28) {
            return null;
        }
        long senderSsrc = unsignedInt(packet, 4);
        long ntpMost = unsignedInt(packet, 8);
        long ntpLeast = unsignedInt(packet, 12);
        long rtpTimestamp = unsignedInt(packet, 16);
        long senderPacketCount = unsignedInt(packet, 20);
        long senderOctetCount = unsignedInt(packet, 24);
        List<RtcpReportBlock> reportBlocks = parseReportBlocks(packet, 28, header.reportCount());
        return new RtcpSenderReportPacket(
                senderSsrc,
                ntpMost,
                ntpLeast,
                rtpTimestamp,
                senderPacketCount,
                senderOctetCount,
                reportBlocks
        );
    }

    private RtcpReceiverReportPacket parseReceiverReport(byte[] packet, RtcpPacketHeader header) {
        if (packet.length < 8) {
            return null;
        }
        long senderSsrc = unsignedInt(packet, 4);
        List<RtcpReportBlock> reportBlocks = parseReportBlocks(packet, 8, header.reportCount());
        return new RtcpReceiverReportPacket(senderSsrc, reportBlocks);
    }

    private List<RtcpReportBlock> parseReportBlocks(byte[] packet, int offset, int reportCount) {
        if (reportCount <= 0) {
            return Collections.emptyList();
        }
        List<RtcpReportBlock> blocks = new ArrayList<RtcpReportBlock>(reportCount);
        int cursor = offset;
        for (int i = 0; i < reportCount; i++) {
            if (cursor + 24 > packet.length) {
                return Collections.unmodifiableList(blocks);
            }
            long ssrc = unsignedInt(packet, cursor);
            int fractionLost = unsignedByte(packet[cursor + 4]);
            int cumulativeLost = signed24(packet, cursor + 5);
            long extendedHighestSequenceNumber = unsignedInt(packet, cursor + 8);
            long interarrivalJitter = unsignedInt(packet, cursor + 12);
            long lastSenderReport = unsignedInt(packet, cursor + 16);
            long delaySinceLastSenderReport = unsignedInt(packet, cursor + 20);
            blocks.add(new RtcpReportBlock(
                    ssrc,
                    fractionLost,
                    cumulativeLost,
                    extendedHighestSequenceNumber,
                    interarrivalJitter,
                    lastSenderReport,
                    delaySinceLastSenderReport
            ));
            cursor += 24;
        }
        return Collections.unmodifiableList(blocks);
    }

    private RtcpGenericNackPacket parseGenericNack(byte[] packet) {
        if (packet.length < 12) {
            return null;
        }
        long senderSsrc = unsignedInt(packet, 4);
        long mediaSsrc = unsignedInt(packet, 8);
        List<Integer> lostSequenceNumbers = new ArrayList<Integer>();
        int cursor = 12;
        while (cursor + 4 <= packet.length) {
            int pid = unsignedShort(packet, cursor);
            int blp = unsignedShort(packet, cursor + 2);
            lostSequenceNumbers.add(Integer.valueOf(pid));
            for (int bit = 0; bit < 16; bit++) {
                if (((blp >>> bit) & 0x01) != 0) {
                    lostSequenceNumbers.add(Integer.valueOf((pid + bit + 1) & 0xFFFF));
                }
            }
            cursor += 4;
        }
        return new RtcpGenericNackPacket(senderSsrc, mediaSsrc, Collections.unmodifiableList(lostSequenceNumbers));
    }

    private RtcpPictureLossIndicationPacket parsePictureLossIndication(byte[] packet) {
        if (packet.length < 12) {
            return null;
        }
        return new RtcpPictureLossIndicationPacket(unsignedInt(packet, 4), unsignedInt(packet, 8));
    }

    private static int signed24(byte[] packet, int offset) {
        int value = (unsignedByte(packet[offset]) << 16)
                | (unsignedByte(packet[offset + 1]) << 8)
                | unsignedByte(packet[offset + 2]);
        if ((value & 0x800000) != 0) {
            value |= 0xFF000000;
        }
        return value;
    }
}
