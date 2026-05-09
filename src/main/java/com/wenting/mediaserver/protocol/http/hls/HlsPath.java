package com.wenting.mediaserver.protocol.http.hls;

final class HlsPath {

    private final String app;
    private final String stream;
    private final String fileName;

    HlsPath(String app, String stream, String fileName) {
        this.app = app;
        this.stream = stream;
        this.fileName = fileName;
    }

    String app() {
        return app;
    }

    String stream() {
        return stream;
    }

    String fileName() {
        return fileName;
    }

    boolean playlist() {
        return "index.m3u8".equals(fileName);
    }

    boolean segment() {
        return fileName != null && fileName.startsWith("seg-") && fileName.endsWith(".ts");
    }
}
