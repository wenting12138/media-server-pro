package com.wenting.mediaserver.bootstrap;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpBootstrap;
import com.wenting.mediaserver.protocol.rtmp.RtmpBootstrap;
import com.wenting.mediaserver.protocol.rtmp.RtmpSessionManager;
import com.wenting.mediaserver.protocol.rtsp.RtspBootstrap;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import com.wenting.mediaserver.protocol.webrtc.NettyWebRtcDatagramSender;
import com.wenting.mediaserver.protocol.webrtc.WebRtcSessionManager;
import com.wenting.mediaserver.protocol.webrtc.WebRtcUdpBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns Netty boss/worker groups and protocol server channels.
 */
public final class MediaServerBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MediaServerBootstrap.class);

    private final MediaServerConfig config;
    private final HttpBootstrap httpBootstrap;
    private final RtmpBootstrap rtmpBootstrap;
    private final RtspBootstrap rtspBootstrap;
    private final WebRtcUdpBootstrap webRtcUdpBootstrap;
    private final StreamRegistry registry;
    private final RtmpSessionManager rtmpSessionManager;
    private final RtspSessionManager sessionManager;
    private final WebRtcSessionManager webRtcSessionManager;
    private final NettyWebRtcDatagramSender webRtcDatagramSender;

    public MediaServerBootstrap(MediaServerConfig config) {
        this.config = config;
        this.rtmpSessionManager = new RtmpSessionManager();
        this.sessionManager = new RtspSessionManager();
        this.webRtcSessionManager = new WebRtcSessionManager();
        this.webRtcDatagramSender = new NettyWebRtcDatagramSender();
        this.registry = new StreamRegistry(sessionManager);
        this.httpBootstrap = new HttpBootstrap(config, registry, webRtcSessionManager, webRtcDatagramSender);
        this.rtmpBootstrap = new RtmpBootstrap(config, registry, rtmpSessionManager);
        this.rtspBootstrap = new RtspBootstrap(config, registry);
        this.webRtcUdpBootstrap = new WebRtcUdpBootstrap(config, webRtcSessionManager, webRtcDatagramSender);
    }

    public void start() throws InterruptedException {
        rtmpBootstrap.start();
        rtspBootstrap.start();
        webRtcUdpBootstrap.start();
        httpBootstrap.start();

        httpBootstrap.await();
    }

    @Override
    public void close() {
        httpBootstrap.close();
        webRtcUdpBootstrap.close();
        rtspBootstrap.close();
        rtmpBootstrap.close();
    }

}
