package com.wenting.mediaserver.core.codec.rtmp;

public final class RtmpMessageType {

    public static final int SET_CHUNK_SIZE = 1;
    public static final int ABORT = 2;
    public static final int ACKNOWLEDGEMENT = 3;
    public static final int USER_CONTROL = 4;
    public static final int WINDOW_ACKNOWLEDGEMENT_SIZE = 5;
    public static final int SET_PEER_BANDWIDTH = 6;
    public static final int AUDIO = 8;
    public static final int VIDEO = 9;
    public static final int DATA_AMF0 = 18;
    public static final int COMMAND_AMF0 = 20;

    private RtmpMessageType() {
    }
}
