package com.wenting.mediaserver.protocol.webrtc.core.ice;

import java.net.InetSocketAddress;

public class PendingSrflxRequest {
    final InetSocketAddress server;
    final byte[] transactionId;
    PendingSrflxRequest(InetSocketAddress server, byte[] transactionId) {
        this.server = server;
        this.transactionId = transactionId;
    }
}