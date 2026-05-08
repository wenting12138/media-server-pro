package com.wenting.mediaserver.core.enums.rtsp;

/**
 * Coarse RTSP session lifecycle for ANNOUNCE/SETUP/RECORD and DESCRIBE/SETUP/PLAY flows.
 */
public enum RtspSessionState {
    INIT,
    ANNOUNCED,
    READY,
    RECORDING,
    PLAYING,
    CLOSED
}
