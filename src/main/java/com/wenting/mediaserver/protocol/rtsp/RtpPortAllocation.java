package com.wenting.mediaserver.protocol.rtsp;

/**
 * One allocated RTP/RTCP UDP port pair.
 */
public final class RtpPortAllocation {

    private final int rtpPort;
    private final int rtcpPort;

    public RtpPortAllocation(int rtpPort, int rtcpPort) {
        if (rtpPort <= 0 || rtcpPort <= 0) {
            throw new IllegalArgumentException("RTP/RTCP ports must be positive");
        }
        if ((rtpPort % 2) != 0) {
            throw new IllegalArgumentException("RTP port must be even");
        }
        if (rtcpPort != rtpPort + 1) {
            throw new IllegalArgumentException("RTCP port must be RTP+1");
        }
        this.rtpPort = rtpPort;
        this.rtcpPort = rtcpPort;
    }

    public int rtpPort() {
        return rtpPort;
    }

    public int rtcpPort() {
        return rtcpPort;
    }

    @Override
    public String toString() {
        return "RtpPortAllocation{rtpPort=" + rtpPort + ", rtcpPort=" + rtcpPort + '}';
    }
}
