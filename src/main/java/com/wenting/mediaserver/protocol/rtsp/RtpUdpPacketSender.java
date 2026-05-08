package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.publish.InboundRtpPacket;

import java.net.InetSocketAddress;

/**
 * Sends one RTP/RTCP UDP packet from a negotiated local port to a remote subscriber address.
 */
public interface RtpUdpPacketSender {

    boolean send(InboundRtpPacket packet, int localPort, InetSocketAddress remoteAddress);
}
