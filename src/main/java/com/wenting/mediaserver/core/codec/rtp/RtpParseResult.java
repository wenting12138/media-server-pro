package com.wenting.mediaserver.core.codec.rtp;

import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPacketHeader;

/**
 * One parsed RTP or RTCP packet.
 */
public final class RtpParseResult {

    private final boolean rtcp;
    private final RtpPacketHeader rtpHeader;
    private final RtcpPacketHeader rtcpHeader;
    private final RtcpPacket rtcpPacket;

    private RtpParseResult(boolean rtcp, RtpPacketHeader rtpHeader, RtcpPacketHeader rtcpHeader, RtcpPacket rtcpPacket) {
        this.rtcp = rtcp;
        this.rtpHeader = rtpHeader;
        this.rtcpHeader = rtcpHeader;
        this.rtcpPacket = rtcpPacket;
    }

    public static RtpParseResult rtp(RtpPacketHeader rtpHeader) {
        return new RtpParseResult(false, rtpHeader, null, null);
    }

    public static RtpParseResult rtcp(RtcpPacketHeader rtcpHeader, RtcpPacket rtcpPacket) {
        return new RtpParseResult(true, null, rtcpHeader, rtcpPacket);
    }

    public boolean rtcp() {
        return rtcp;
    }

    public boolean rtp() {
        return !rtcp;
    }

    public RtpPacketHeader rtpHeader() {
        return rtpHeader;
    }

    public RtcpPacketHeader rtcpHeader() {
        return rtcpHeader;
    }

    public RtcpPacket rtcpPacket() {
        return rtcpPacket;
    }
}
