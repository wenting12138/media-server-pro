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
    private final byte[] h264Sps;
    private final byte[] h264Pps;
    private final byte[] h265Vps;
    private final byte[] h265Sps;
    private final byte[] h265Pps;

    public VideoTrack() {
        this("", CodecType.UNKNOWN, null, 0, false, null, null, null, null, null);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress) {
        this(trackId, codecType, connectionAddress, 0, false, null, null, null, null, null);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress, int clockRate) {
        this(trackId, codecType, connectionAddress, clockRate, false, null, null, null, null, null);
    }

    public VideoTrack(String trackId, CodecType codecType, String connectionAddress, int clockRate, boolean outOfBandParameterSetsReady) {
        this(trackId, codecType, connectionAddress, clockRate, outOfBandParameterSetsReady, null, null, null, null, null);
    }

    public VideoTrack(
            String trackId,
            CodecType codecType,
            String connectionAddress,
            int clockRate,
            boolean outOfBandParameterSetsReady,
            byte[] h264Sps,
            byte[] h264Pps,
            byte[] h265Vps,
            byte[] h265Sps,
            byte[] h265Pps
    ) {
        this.trackId = trackId == null ? "" : trackId;
        this.codecType = codecType == null ? CodecType.UNKNOWN : codecType;
        this.trackType = TrackType.VIDEO;
        this.connectionAddress = connectionAddress == null ? "" : connectionAddress.trim();
        this.clockRate = clockRate;
        this.outOfBandParameterSetsReady = outOfBandParameterSetsReady;
        this.h264Sps = copy(h264Sps);
        this.h264Pps = copy(h264Pps);
        this.h265Vps = copy(h265Vps);
        this.h265Sps = copy(h265Sps);
        this.h265Pps = copy(h265Pps);
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

    @Override
    public byte[] h264Sps() {
        return copy(h264Sps);
    }

    @Override
    public byte[] h264Pps() {
        return copy(h264Pps);
    }

    @Override
    public byte[] h265Vps() {
        return copy(h265Vps);
    }

    @Override
    public byte[] h265Sps() {
        return copy(h265Sps);
    }

    @Override
    public byte[] h265Pps() {
        return copy(h265Pps);
    }

    private static byte[] copy(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes == null ? null : new byte[0];
        }
        byte[] copy = new byte[bytes.length];
        System.arraycopy(bytes, 0, copy, 0, bytes.length);
        return copy;
    }
}
