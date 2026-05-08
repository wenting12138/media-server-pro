package com.wenting.mediaserver.core.track;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;

public class AudioTrack implements ITrack {
    private final String trackId;
    private final CodecType codecType;
    private final TrackType trackType;
    private final String connectionAddress;

    private final int sampleRate;
    private final int channels;
    private final int bitrate;

    public AudioTrack() {
        this("", CodecType.UNKNOWN, null, 0, 0, 0);
    }

    public AudioTrack(String trackId, CodecType codecType, String connectionAddress, int sampleRate, int channels, int bitrate) {
        this.trackId = trackId == null ? "" : trackId;
        this.codecType = codecType == null ? CodecType.UNKNOWN : codecType;
        this.trackType = TrackType.AUDIO;
        this.connectionAddress = connectionAddress == null ? "" : connectionAddress.trim();
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrate = bitrate;
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
        return sampleRate;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int bitrate() {
        return bitrate;
    }
}
