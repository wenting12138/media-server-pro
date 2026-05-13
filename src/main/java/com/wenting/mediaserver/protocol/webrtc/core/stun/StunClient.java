package com.wenting.mediaserver.protocol.webrtc.core.stun;


import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * STUN client that sends Binding Requests and handles responses.
 *
 * 典型用法:
 *   StunClient client = new StunClient(transport);
 *   client.sendBindingRequest("stun.l.google.com", 19302)
 *         .thenAccept(addr ->
 *             System.out.println("公网地址: " + addr));
 */
public class StunClient {
    private static final Logger LOG = Logger.getLogger(StunClient.class.getName());

    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private final UdpTransport transport;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stun-timeout");
            t.setDaemon(true);
            return t;
        });

    private volatile boolean started = false;

    public StunClient(UdpTransport transport) {
        this.transport = transport;
    }

    /** 启动 STUN 客户端，注册 UDP 包回调 */
    public void start() {
        if (!started) {
            started = true;
            transport.setPacketHandler(this::handlePacket);
        }
    }

    /** 向 STUN 服务器发送 Binding Request（host:port 形式） */
    public CompletableFuture<InetSocketAddress> sendBindingRequest(String host, int port) {
        return sendBindingRequest(new InetSocketAddress(host, port));
    }

    /** 向 STUN 服务器发送 Binding Request */
    public CompletableFuture<InetSocketAddress> sendBindingRequest(InetSocketAddress server) {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);

        StunMessage request = StunMessage.createBindingRequest(transactionId);
        byte[] data = request.encode();

        String key = bytesToHex(transactionId);
        PendingRequest pendingReq = new PendingRequest(server, transactionId);
        pending.put(key, pendingReq);

        transport.send(data, server)
            .exceptionally(ex -> {
                pending.remove(key);
                pendingReq.future.completeExceptionally(ex);
                return null;
            });

        timeoutScheduler.schedule(() -> {
            PendingRequest removed = pending.remove(key);
            if (removed != null && !removed.future.isDone()) {
                removed.future.completeExceptionally(
                    new IOException("STUN request timed out after "
                        + DEFAULT_TIMEOUT_MS + "ms"));
            }
        }, DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        return pendingReq.future;
    }

    /** 关闭客户端，释放资源 */
    public void shutdown() {
        timeoutScheduler.shutdown();
    }

    // ---- 内部方法 ----

    private void handlePacket(byte[] data, InetSocketAddress remote) {
        try {
            StunMessage msg = StunMessage.decode(data);
            if (msg.isBindingResponse()) {
                String key = bytesToHex(msg.getTransactionId());
                PendingRequest req = pending.remove(key);
                if (req != null && !req.future.isDone()) {
                    InetSocketAddress mapped = msg.getXorMappedAddress();
                    if (mapped != null) {
                        LOG.info("STUN binding response from " + remote
                            + " mapped address: " + mapped);
                        req.future.complete(mapped);
                    } else {
                        req.future.completeExceptionally(
                            new IOException("No XOR-MAPPED-ADDRESS in response"));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // 不是 STUN 包，忽略
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- 内部类型 ----

    private static class PendingRequest {
        final InetSocketAddress server;
        final byte[] transactionId;
        final CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();

        PendingRequest(InetSocketAddress server, byte[] transactionId) {
            this.server = server;
            this.transactionId = transactionId;
        }
    }
}
