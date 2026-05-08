package com.wenting.mediaserver.core.publish.video;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;

/**
 * Detects whether one H264 RTP packet starts an IDR key frame.
 */
public final class H264RtpKeyFrameDetector {

    private static final int NAL_TYPE_IDR = 5;
    private static final int NAL_TYPE_STAP_A = 24;
    private static final int NAL_TYPE_FU_A = 28;

    public boolean isKeyFrame(byte[] packet, RtpPacketHeader header) {
        if (packet == null || header == null || header.payloadLength() <= 0) {
            return false;
        }
        int payloadOffset = header.payloadOffset();
        if (payloadOffset >= packet.length) {
            return false;
        }
        int nalType = packet[payloadOffset] & 0x1F;
        if (nalType > 0 && nalType < NAL_TYPE_STAP_A) {
            return nalType == NAL_TYPE_IDR;
        }
        if (nalType == NAL_TYPE_STAP_A) {
            return containsIdrInStapA(packet, payloadOffset + 1, packet.length);
        }
        if (nalType == NAL_TYPE_FU_A) {
            return isIdrFuA(packet, payloadOffset, packet.length);
        }
        return false;
    }

    private boolean containsIdrInStapA(byte[] packet, int offset, int limit) {
        int cursor = offset;
        while (cursor + 2 <= limit) {
            int nalUnitLength = ((packet[cursor] & 0xFF) << 8) | (packet[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalUnitLength <= 0 || cursor + nalUnitLength > limit) {
                return false;
            }
            int nalType = packet[cursor] & 0x1F;
            if (nalType == NAL_TYPE_IDR) {
                return true;
            }
            cursor += nalUnitLength;
        }
        return false;
    }

    private boolean isIdrFuA(byte[] packet, int payloadOffset, int limit) {
        if (payloadOffset + 1 >= limit) {
            return false;
        }
        int fuHeader = packet[payloadOffset + 1] & 0xFF;
        boolean start = (fuHeader & 0x80) != 0;
        int nalType = fuHeader & 0x1F;
        return start && nalType == NAL_TYPE_IDR;
    }
}
