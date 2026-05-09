package com.wenting.mediaserver.config;

/**
 * Runtime configuration. Extend with YAML/properties when needed.
 */
public final class MediaServerConfig {

    private static final int DEFAULT_HTTP = 18080;
    private static final int DEFAULT_RTSP = 1554;
    private static final int DEFAULT_RTMP = 11935;
    private static final int DEFAULT_RTP_PORT_MIN = 20000;
    private static final int DEFAULT_RTP_PORT_MAX = 30000;
    private static final String DEFAULT_HLS_STORAGE = "memory";
    private static final String DEFAULT_HLS_DIRECTORY = "D:/workspace/github/wenting/mediaserver_data/hls";

    private final int httpPort;
    private final int rtspPort;
    private final int rtmpPort;
    private final int rtpPortMin;
    private final int rtpPortMax;
    private final String hlsStorage;
    private final String hlsDirectory;

    public MediaServerConfig(
            int httpPort,
            int rtspPort,
            int rtmpPort,
            int rtpPortMin,
            int rtpPortMax,
            String hlsStorage,
            String hlsDirectory
    ) {
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
        this.rtmpPort = rtmpPort;
        this.rtpPortMin = rtpPortMin;
        this.rtpPortMax = rtpPortMax;
        this.hlsStorage = normalizeHlsStorage(hlsStorage);
        this.hlsDirectory = normalizeHlsDirectory(hlsDirectory);
    }

    public MediaServerConfig(
            int httpPort,
            int rtspPort,
            int rtmpPort,
            int rtpPortMin,
            int rtpPortMax
    ) {
        this(
                httpPort,
                rtspPort,
                rtmpPort,
                rtpPortMin,
                rtpPortMax,
                DEFAULT_HLS_STORAGE,
                DEFAULT_HLS_DIRECTORY
        );
    }

    public static MediaServerConfig fromEnvironment() {
        int http = parsePort(System.getenv("MEDIA_HTTP_PORT"), DEFAULT_HTTP);
        int rtsp = parsePort(System.getenv("MEDIA_RTSP_PORT"), DEFAULT_RTSP);
        int rtmp = parsePort(System.getenv("MEDIA_RTMP_PORT"), DEFAULT_RTMP);
        int rtpMin = parsePort(System.getenv("MEDIA_RTP_PORT_MIN"), DEFAULT_RTP_PORT_MIN);
        int rtpMax = parsePort(System.getenv("MEDIA_RTP_PORT_MAX"), DEFAULT_RTP_PORT_MAX);
        String hlsStorage = normalizeHlsStorage(System.getenv("MEDIA_HLS_STORAGE"));
        String hlsDirectory = normalizeHlsDirectory(System.getenv("MEDIA_HLS_DIRECTORY"));
        if (rtpMin > rtpMax) {
            int t = rtpMin;
            rtpMin = rtpMax;
            rtpMax = t;
        }
        if (((rtpMax - rtpMin) + 1) < 2) {
            rtpMin = DEFAULT_RTP_PORT_MIN;
            rtpMax = DEFAULT_RTP_PORT_MAX;
        }
        return new MediaServerConfig(
                http,
                rtsp,
                rtmp,
                rtpMin,
                rtpMax,
                hlsStorage,
                hlsDirectory
        );
    }

    private static int parsePort(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            int p = Integer.parseInt(raw.trim());
            if (p <= 0 || p > 65535) {
                return fallback;
            }
            return p;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int httpPort() {
        return httpPort;
    }

    public int rtspPort() {
        return rtspPort;
    }

    public int rtmpPort() {
        return rtmpPort;
    }

    public int rtpPortMin() {
        return rtpPortMin;
    }

    public int rtpPortMax() {
        return rtpPortMax;
    }

    public String hlsStorage() {
        return hlsStorage;
    }

    public String hlsDirectory() {
        return hlsDirectory;
    }

    public boolean hlsFileStorageEnabled() {
        return "file".equals(hlsStorage);
    }

    public String version() {
        String v = MediaServerConfig.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0-SNAPSHOT";
    }

    public String serverId() {
        return "media-server";
    }

    private static String normalizeHlsStorage(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_HLS_STORAGE;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "file".equals(normalized) ? "file" : DEFAULT_HLS_STORAGE;
    }

    private static String normalizeHlsDirectory(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_HLS_DIRECTORY;
        }
        return raw.trim();
    }
}
