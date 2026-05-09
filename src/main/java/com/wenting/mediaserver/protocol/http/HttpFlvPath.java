package com.wenting.mediaserver.protocol.http;

final class HttpFlvPath {

    private final String app;
    private final String stream;

    HttpFlvPath(String app, String stream) {
        this.app = app;
        this.stream = stream;
    }

    String app() {
        return app;
    }

    String stream() {
        return stream;
    }
}
