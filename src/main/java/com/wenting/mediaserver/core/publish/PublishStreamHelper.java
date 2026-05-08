package com.wenting.mediaserver.core.publish;

public class PublishStreamHelper {

    public static String trackLabel(String trackId) {
        return trackId == null || trackId.trim().isEmpty() ? "default" : trackId.trim();
    }

}
