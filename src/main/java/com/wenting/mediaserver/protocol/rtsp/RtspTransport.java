package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;

/**
 * Negotiated RTSP transport details for one media setup.
 */
public final class RtspTransport {

    private final RtspTransportMode mode;
    private final Integer clientRtpPort;
    private final Integer clientRtcpPort;
    private final Integer serverRtpPort;
    private final Integer serverRtcpPort;
    private final Integer interleavedRtpChannel;
    private final Integer interleavedRtcpChannel;
    private final String originalHeader;

    public RtspTransport(
            RtspTransportMode mode,
            Integer clientRtpPort,
            Integer clientRtcpPort,
            Integer serverRtpPort,
            Integer serverRtcpPort,
            Integer interleavedRtpChannel,
            Integer interleavedRtcpChannel,
            String originalHeader
    ) {
        this.mode = mode == null ? RtspTransportMode.UNKNOWN : mode;
        this.clientRtpPort = clientRtpPort;
        this.clientRtcpPort = clientRtcpPort;
        this.serverRtpPort = serverRtpPort;
        this.serverRtcpPort = serverRtcpPort;
        this.interleavedRtpChannel = interleavedRtpChannel;
        this.interleavedRtcpChannel = interleavedRtcpChannel;
        this.originalHeader = originalHeader;
    }

    public static RtspTransport unknown(String originalHeader) {
        return new RtspTransport(RtspTransportMode.UNKNOWN, null, null, null, null, null, null, originalHeader);
    }

    public RtspTransportMode mode() {
        return mode;
    }

    public Integer clientRtpPort() {
        return clientRtpPort;
    }

    public Integer clientRtcpPort() {
        return clientRtcpPort;
    }

    public Integer serverRtpPort() {
        return serverRtpPort;
    }

    public Integer serverRtcpPort() {
        return serverRtcpPort;
    }

    public Integer interleavedRtpChannel() {
        return interleavedRtpChannel;
    }

    public Integer interleavedRtcpChannel() {
        return interleavedRtcpChannel;
    }

    public String originalHeader() {
        return originalHeader;
    }

    public boolean usesUdp() {
        return mode == RtspTransportMode.RTP_UDP;
    }

    public boolean usesInterleavedTcp() {
        return mode == RtspTransportMode.RTP_TCP_INTERLEAVED;
    }

    public RtspTransport withServerPorts(RtpPortAllocation allocation) {
        if (allocation == null) {
            return this;
        }
        return new RtspTransport(
                mode,
                clientRtpPort,
                clientRtcpPort,
                allocation.rtpPort(),
                allocation.rtcpPort(),
                interleavedRtpChannel,
                interleavedRtcpChannel,
                originalHeader
        );
    }

    public String toResponseHeaderValue() {
        if (originalHeader == null || originalHeader.trim().isEmpty()) {
            return null;
        }
        String value = originalHeader;
        if (serverRtpPort != null && serverRtcpPort != null && value.indexOf("server_port=") < 0) {
            value = value + ";server_port=" + serverRtpPort + "-" + serverRtcpPort;
        }
        return value;
    }

    @Override
    public String toString() {
        return "RtspTransport{"
                + "mode=" + mode
                + ", clientRtpPort=" + clientRtpPort
                + ", clientRtcpPort=" + clientRtcpPort
                + ", serverRtpPort=" + serverRtpPort
                + ", serverRtcpPort=" + serverRtcpPort
                + ", interleavedRtpChannel=" + interleavedRtpChannel
                + ", interleavedRtcpChannel=" + interleavedRtcpChannel
                + '}';
    }
}
