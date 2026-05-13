package com.wenting.mediaserver.protocol.webrtc.transport;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-session datagram facade backed by a shared UDP listener/sender.
 *
 * Incoming packets are pushed in by the outer UDP demux layer through
 * {@link #receive(byte[], InetSocketAddress)}.
 */
public final class SessionDatagramIo implements DatagramIo {

    private final InetSocketAddress localAddress;
    private final DatagramIoSender sender;
    private volatile UdpTransport.PacketHandler packetHandler;
    private volatile boolean closed;

    public SessionDatagramIo(InetSocketAddress localAddress, DatagramIoSender sender) {
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void setPacketHandler(UdpTransport.PacketHandler handler) {
        this.packetHandler = handler;
    }

    @Override
    public void start() {
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
        if (closed) {
            CompletableFuture<Void> future = new CompletableFuture<Void>();
            future.completeExceptionally(new IllegalStateException("Session datagram transport is closed"));
            return future;
        }
        return sender.send(data, target);
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        if (closed || data == null) {
            return;
        }
        UdpTransport.PacketHandler currentHandler = packetHandler;
        if (currentHandler != null) {
            currentHandler.onPacket(data, remoteAddress);
        }
    }

    @Override
    public void close(long timeout, TimeUnit unit) {
        close();
    }

    @Override
    public void close() {
        closed = true;
        packetHandler = null;
    }
}
