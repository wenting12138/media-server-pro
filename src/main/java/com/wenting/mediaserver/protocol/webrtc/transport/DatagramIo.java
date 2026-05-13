package com.wenting.mediaserver.protocol.webrtc.transport;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal datagram transport abstraction for WebRTC components.
 */
public interface DatagramIo extends AutoCloseable {

    void setPacketHandler(UdpTransport.PacketHandler handler);

    void start() throws InterruptedException;

    InetSocketAddress getLocalAddress();

    CompletableFuture<Void> send(byte[] data, InetSocketAddress target);

    void close(long timeout, TimeUnit unit);

    @Override
    void close();
}
