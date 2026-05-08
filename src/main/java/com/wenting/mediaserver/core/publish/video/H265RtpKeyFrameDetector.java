package com.wenting.mediaserver.core.publish.video;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;

/**
 * Detects whether one H265 RTP packet starts a random-access key frame.
 */
public final class H265RtpKeyFrameDetector {

    private static final int NAL_TYPE_BLA_W_LP = 16;
    private static final int NAL_TYPE_BLA_W_RADL = 17;
    private static final int NAL_TYPE_BLA_N_LP = 18;
    private static final int NAL_TYPE_IDR_W_RADL = 19;
    private static final int NAL_TYPE_IDR_N_LP = 20;
    private static final int NAL_TYPE_CRA = 21;
    private static final int NAL_TYPE_AP = 48;
    private static final int NAL_TYPE_FU = 49;

    public boolean isKeyFrame(byte[] packet, RtpPacketHeader header) {
        if (packet == null || header == null || header.payloadLength() < 2) {
            return false;
        }
        int payloadOffset = header.payloadOffset();
        if (payloadOffset + 1 >= packet.length) {
            return false;
        }
        int nalType = nalType(packet, payloadOffset);
        if (isRandomAccessNal(nalType)) {
            return true;
        }
        if (nalType == NAL_TYPE_AP) {
            return containsRandomAccessNalInAp(packet, payloadOffset + 2, packet.length);
        }
        if (nalType == NAL_TYPE_FU) {
            return isRandomAccessFu(packet, payloadOffset, packet.length);
        }
        return false;
    }

    private boolean containsRandomAccessNalInAp(byte[] packet, int offset, int limit) {
        int cursor = offset;
        while (cursor + 2 <= limit) {
            int nalUnitLength = ((packet[cursor] & 0xFF) << 8) | (packet[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalUnitLength <= 0 || cursor + nalUnitLength > limit) {
                return false;
            }
            if (isRandomAccessNal(nalType(packet, cursor))) {
                return true;
            }
            cursor += nalUnitLength;
        }
        return false;
    }

    private boolean isRandomAccessFu(byte[] packet, int payloadOffset, int limit) {
        if (payloadOffset + 2 >= limit) {
            return false;
        }
        int fuHeader = packet[payloadOffset + 2] & 0xFF;
        boolean start = (fuHeader & 0x80) != 0;
        int nalType = fuHeader & 0x3F;
        return start && isRandomAccessNal(nalType);
    }

    private boolean isRandomAccessNal(int nalType) {
        return nalType == NAL_TYPE_BLA_W_LP
                || nalType == NAL_TYPE_BLA_W_RADL
                || nalType == NAL_TYPE_BLA_N_LP
                || nalType == NAL_TYPE_IDR_W_RADL
                || nalType == NAL_TYPE_IDR_N_LP
                || nalType == NAL_TYPE_CRA;
    }

    private int nalType(byte[] packet, int payloadOffset) {
        return (packet[payloadOffset] & 0x7E) >> 1;
    }
}
