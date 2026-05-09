package com.wenting.mediaserver.protocol.webrtc;

public final class WebRtcOfferParser {

    public WebRtcOfferSummary parse(String sdp) {
        if (sdp == null || sdp.trim().isEmpty()) {
            return new WebRtcOfferSummary(false, "0", null);
        }
        String[] lines = sdp.replace("\r", "").split("\n");
        boolean inVideo = false;
        boolean hasVideo = false;
        String videoMid = "0";
        Integer h264PayloadType = null;
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video ");
                if (inVideo) {
                    hasVideo = true;
                }
                continue;
            }
            if (!inVideo) {
                continue;
            }
            if (line.startsWith("a=mid:")) {
                videoMid = line.substring("a=mid:".length()).trim();
                continue;
            }
            if (line.startsWith("a=rtpmap:")) {
                int spaceIndex = line.indexOf(' ');
                if (spaceIndex <= "a=rtpmap:".length()) {
                    continue;
                }
                String payloadType = line.substring("a=rtpmap:".length(), spaceIndex).trim();
                String codecSpec = line.substring(spaceIndex + 1).trim().toUpperCase(java.util.Locale.ROOT);
                if (codecSpec.startsWith("H264/")) {
                    try {
                        h264PayloadType = Integer.valueOf(Integer.parseInt(payloadType));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new WebRtcOfferSummary(hasVideo, videoMid, h264PayloadType);
    }
}
