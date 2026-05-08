package com.wenting.mediaserver.core.track;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;

public class VideoTrack implements ITrack {
    private final String trackId;
    private final CodecType codecType;
    private final TrackType trackType;
    private final String connectionAddress;
    private final int clockRate;
    private final boolean outOfBandParameterSetsReady;

    public VideoTrack() {
        this("", CodecType.UNKNOWN, null, 0, false);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress) {
        this(trackId, codecType, connectionAddress, 0, false);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress, int clockRate) {
        this(trackId, codecType, connectionAddress, clockRate, false);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress, int clockRate, boolean outOfBandParameterSetsReady) {
        this.trackId = trackId == null ? "" : trackId;
        this.codecType = codecType == null ? CodecType.UNKNOWN : codecType;
        this.trackType = TrackType.VIDEO;
        this.connectionAddress = connectionAddress == null ? "" : connectionAddress.trim();
        this.clockRate = clockRate;
        this.outOfBandParameterSetsReady = outOfBandParameterSetsReady;
    }

    @Override
    public String trackId() {
        return trackId;
    }

    @Override
    public CodecType codecType() {
        return codecType;
    }

    @Override
    public TrackType trackType() {
        return trackType;
    }

    @Override
    public String connectionAddress() {
        return connectionAddress;
    }

    @Override
    public int clockRate() {
        return clockRate;
    }

    @Override
    public boolean outOfBandParameterSetsReady() {
        return outOfBandParameterSetsReady;
    }
}
