package com.wenting.mediaserver.protocol.http.webrtc;

public final class WebRtcPlayRequest {

    private String app;
    private String stream;
    private String sdp;

    public WebRtcPlayRequest() {
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getSdp() {
        return sdp;
    }

    public void setSdp(String sdp) {
        this.sdp = sdp;
    }
}
