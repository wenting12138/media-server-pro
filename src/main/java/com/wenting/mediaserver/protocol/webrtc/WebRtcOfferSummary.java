package com.wenting.mediaserver.protocol.webrtc;

public final class WebRtcOfferSummary {

    private final boolean hasVideo;
    private final String videoMid;
    private final Integer h264PayloadType;

    public WebRtcOfferSummary(boolean hasVideo, String videoMid, Integer h264PayloadType) {
        this.hasVideo = hasVideo;
        this.videoMid = videoMid == null || videoMid.trim().isEmpty() ? "0" : videoMid.trim();
        this.h264PayloadType = h264PayloadType;
    }

    public boolean hasVideo() {
        return hasVideo;
    }

    public String videoMid() {
        return videoMid;
    }

    public Integer h264PayloadType() {
        return h264PayloadType;
    }

    public boolean supportsH264Video() {
        return hasVideo && h264PayloadType != null;
    }
}
