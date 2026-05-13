package com.wenting.mediaserver.protocol.webrtc.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty-backed UDP transport.
 *
 * 基于 Netty 4.1 的 NIO UDP 传输层，替代原始 NIO Selector 实现。
 * 使用 Netty 的 EventLoopGroup 管理线程，Bootstrap 配置管道。
 *
 * 发送: channel.writeAndFlush(DatagramPacket)
 * 接收: SimpleChannelInboundHandler<DatagramPacket> 处理入站包
 */
public class UdpTransport implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(UdpTransport.class.getName());

    private final EventLoopGroup group;
    private final Bootstrap bootstrap;
    private final ScheduledExecutorService timeoutExecutor;
    private Channel channel;
    private volatile PacketHandler packetHandler;
    private volatile InetSocketAddress localAddress;

    public UdpTransport() {
        this(0);
    }

    public UdpTransport(int localPort) {
        this.group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "udp-netty");
            t.setDaemon(true);
            return t;
        });

        this.timeoutExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "udp-timeout");
            t.setDaemon(true);
            return t;
        });
        ((ScheduledThreadPoolExecutor) this.timeoutExecutor).setRemoveOnCancelPolicy(true);

        this.bootstrap = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override
                protected void initChannel(NioDatagramChannel ch) {
                    ch.pipeline().addLast(new UdpInboundHandler());
                }
            })
            .localAddress(new InetSocketAddress(localPort));
    }

    /** 设置收到 UDP 包的回调 */
    public void setPacketHandler(PacketHandler handler) {
        this.packetHandler = handler;
    }

    /** 启动 Netty 并绑定端口 */
    public void start() throws InterruptedException {
        channel = bootstrap.bind().sync().channel();
        localAddress = (InetSocketAddress) channel.localAddress();
        LOG.info("UDP transport bound to " + localAddress);
    }

    /** 关闭传输层，等待指定时间让 Netty 优雅退出 */
    public void close(long timeout, TimeUnit unit) {
        if (channel != null) {
            try {
                channel.close().await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        group.shutdownGracefully(0, Math.max(1000, unit.toMillis(timeout)), TimeUnit.MILLISECONDS);
        timeoutExecutor.shutdownNow();
        try {
            timeoutExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        close(5, TimeUnit.SECONDS);
    }

    /** 获取本地绑定的地址 */
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    /** 异步发送 UDP 数据包 */
    public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        DatagramPacket packet = new DatagramPacket(
            io.netty.buffer.Unpooled.wrappedBuffer(data), target);

        channel.writeAndFlush(packet).addListener(f -> {
            if (f.isSuccess()) {
                future.complete(null);
            } else {
                future.completeExceptionally(f.cause());
            }
        });
        return future;
    }

    /**
     * 带超时的异步发送。在指定时间内未完成则 CompletableFuture 超时失败。
     * Java 1.8 兼容: 手动实现 orTimeout 语义。
     */
    public CompletableFuture<Void> send(byte[] data, InetSocketAddress target,
                                         long timeout, TimeUnit unit) {
        CompletableFuture<Void> future = send(data, target);
        ScheduledFuture<?> timer = timeoutExecutor.schedule(() -> {
            future.completeExceptionally(
                new java.util.concurrent.TimeoutException("UDP send timeout after "
                    + unit.toMillis(timeout) + "ms"));
        }, timeout, unit);
        future.whenComplete((v, t) -> timer.cancel(false));
        return future;
    }

    /** 获取超时调度器（供外部使用，例如定期发送 keepalive） */
    public ScheduledExecutorService getTimeoutExecutor() {
        return timeoutExecutor;
    }

    // ---- 内部处理器 ----

    private class UdpInboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            PacketHandler handler = packetHandler;
            if (handler == null) return;

            byte[] data = new byte[msg.content().readableBytes()];
            msg.content().readBytes(data);
            handler.onPacket(data, msg.sender());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.log(Level.WARNING, "UDP handler error", cause);
        }
    }

    @FunctionalInterface
    public interface PacketHandler {
        void onPacket(byte[] data, InetSocketAddress remoteAddress);
    }
}
