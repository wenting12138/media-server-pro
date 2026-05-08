package com.wenting.mediaserver.core.enums.rtsp;

import java.util.Locale;

/**
 * Supported RTSP request methods handled by the connection state machine.
 */
public enum RtspRequestType {
    OPTIONS,
    ANNOUNCE,
    DESCRIBE,
    SETUP,
    RECORD,
    PLAY,
    TEARDOWN,
    GET_PARAMETER,
    SET_PARAMETER,
    UNKNOWN;

    public static RtspRequestType fromMethod(String method) {
        String normalized = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if ("OPTIONS".equals(normalized)) {
            return OPTIONS;
        }
        if ("ANNOUNCE".equals(normalized)) {
            return ANNOUNCE;
        }
        if ("DESCRIBE".equals(normalized)) {
            return DESCRIBE;
        }
        if ("SETUP".equals(normalized)) {
            return SETUP;
        }
        if ("RECORD".equals(normalized)) {
            return RECORD;
        }
        if ("PLAY".equals(normalized)) {
            return PLAY;
        }
        if ("TEARDOWN".equals(normalized)) {
            return TEARDOWN;
        }
        if ("GET_PARAMETER".equals(normalized)) {
            return GET_PARAMETER;
        }
        if ("SET_PARAMETER".equals(normalized)) {
            return SET_PARAMETER;
        }
        return UNKNOWN;
    }

    public String methodName() {
        return name();
    }
}
