package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.bootstrap.IServerBootstrap;
import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * Shared UDP listener for server-side WebRTC ICE/DTLS/RTP traffic.
 */
public final class WebRtcUdpBootstrap implements IServerBootstrap, DatagramIoSender {

    private static final Logger log = LoggerFactory.getLogger(WebRtcUdpBootstrap.class);

    private final MediaServerConfig config;
    private final WebRtcSessionManager sessionManager;
    private final WebRtcPublishSessionManager publishSessionManager;
    private final UdpTransport transport;

    public WebRtcUdpBootstrap(MediaServerConfig config, WebRtcSessionManager sessionManager) {
        this(config, sessionManager, null);
    }

    public WebRtcUdpBootstrap(
            MediaServerConfig config,
            WebRtcSessionManager sessionManager,
            WebRtcPublishSessionManager publishSessionManager
    ) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.publishSessionManager = publishSessionManager;
        this.transport = new UdpTransport(config == null ? 0 : config.webrtcUdpPort());
    }

    @Override
    public void start() throws InterruptedException {
        transport.setPacketHandler(new WebRtcUdpPacketHandler(sessionManager, publishSessionManager));
        transport.start();
        log.info("WebRTC UDP listening on {}", transport.getLocalAddress());
    }

    @Override
    public void await() {
    }

    @Override
    public void close() {
        transport.close();
    }

    @Override
    public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
        return transport.send(data, target);
    }

    public InetSocketAddress localAddress() {
        return transport.getLocalAddress();
    }
}
