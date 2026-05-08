package com.wenting.mediaserver.bootstrap;

import com.wenting.mediaserver.config.MediaServerConfig;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.http.HttpBootstrap;
import com.wenting.mediaserver.protocol.rtmp.RtmpBootstrap;
import com.wenting.mediaserver.protocol.rtmp.RtmpSessionManager;
import com.wenting.mediaserver.protocol.rtsp.RtspBootstrap;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
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
    private final StreamRegistry registry;
    private final RtmpSessionManager rtmpSessionManager;
    private final RtspSessionManager sessionManager;

    public MediaServerBootstrap(MediaServerConfig config) {
        this.config = config;
        this.rtmpSessionManager = new RtmpSessionManager();
        this.sessionManager = new RtspSessionManager();
        this.registry = new StreamRegistry(sessionManager);
        this.httpBootstrap = new HttpBootstrap(config, registry);
        this.rtmpBootstrap = new RtmpBootstrap(config, registry, rtmpSessionManager);
        this.rtspBootstrap = new RtspBootstrap(config, registry);
    }

    public void start() throws InterruptedException {
        rtmpBootstrap.start();
        rtspBootstrap.start();
        httpBootstrap.start();

        httpBootstrap.await();
    }

    @Override
    public void close() {
        httpBootstrap.close();
        rtspBootstrap.close();
        rtmpBootstrap.close();
    }

}
