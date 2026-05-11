package com.wenting.mediaserver.protocol.webrtc;

import java.net.InetSocketAddress;

public interface WebRtcDatagramSender {

    void send(byte[] payload, InetSocketAddress remoteAddress);
}
