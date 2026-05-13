package com.wenting.mediaserver.protocol.webrtc.transport;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Shared UDP sender abstraction for server-managed WebRTC sessions.
 */
public interface DatagramIoSender {

    CompletableFuture<Void> send(byte[] data, InetSocketAddress target);
}
