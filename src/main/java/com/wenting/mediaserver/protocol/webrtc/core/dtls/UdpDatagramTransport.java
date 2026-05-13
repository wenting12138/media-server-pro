package com.wenting.mediaserver.protocol.webrtc.core.dtls;

import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.bouncycastle.tls.DatagramTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Adapter from our UdpTransport to BouncyCastle's DatagramTransport.
 *
 * BouncyCastle 的 DTLS 协议需要实现 DatagramTransport 接口
 * （同步 receive/send），而我们的 UdpTransport 是异步回调式的。
 * 此适配器用 BlockingQueue 桥接两者。
 */
public class UdpDatagramTransport implements DatagramTransport {

    private static final int MTU = 1500;

    private final UdpTransport transport;
    private final InetSocketAddress peer;
    private final LinkedBlockingQueue<byte[]> receiveQueue;

    private volatile boolean closed = false;

    public UdpDatagramTransport(UdpTransport transport, InetSocketAddress peer) {
        this(transport, peer, new LinkedBlockingQueue<byte[]>());
        // Register inbound packet handler to feed the queue
        transport.setPacketHandler((data, remote) -> {
            if (!closed) {
                receiveQueue.offer(data);
            }
        });
    }

    /**
     * Constructor with an externally-managed receive queue.
     * The caller is responsible for feeding packets into the receiveQueue.
     */
    public UdpDatagramTransport(UdpTransport transport, InetSocketAddress peer,
                                 LinkedBlockingQueue<byte[]> receiveQueue) {
        this.transport = transport;
        this.peer = peer;
        this.receiveQueue = receiveQueue;
    }

    @Override
    public int getReceiveLimit() {
        return MTU;
    }

    @Override
    public int getSendLimit() {
        return MTU;
    }

    @Override
    public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
        if (closed) throw new IOException("Transport closed");

        try {
            byte[] packet = receiveQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (packet == null) {
                return -1; // timeout
            }
            int copyLen = Math.min(packet.length, len);
            System.arraycopy(packet, 0, buf, off, copyLen);
            return copyLen;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    @Override
    public void send(byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("Transport closed");

        byte[] data = new byte[len];
        System.arraycopy(buf, off, data, 0, len);

        try {
            transport.send(data, peer).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Send failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        receiveQueue.clear();
    }
}
