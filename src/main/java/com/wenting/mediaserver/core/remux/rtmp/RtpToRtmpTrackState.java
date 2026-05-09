package com.wenting.mediaserver.core.remux.rtmp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class RtpToRtmpTrackState {

    private Long firstRtpTimestamp;
    private Long currentAccessUnitTimestamp;
    private final List<byte[]> currentAccessUnitNals = new ArrayList<byte[]>();
    private boolean currentAccessUnitKeyFrame;

    private Long pendingFragmentTimestamp;
    private int pendingFragmentNalType = -1;
    private ByteArrayOutputStream pendingFragmentBuffer;

    private byte[] h264Sps;
    private byte[] h264Pps;
    private byte[] h265Vps;
    private byte[] h265Sps;
    private byte[] h265Pps;
    private boolean configDirty = true;
    private boolean configSent;
    private byte[] aacConfig;
    private boolean aacConfigDirty = true;
    private boolean aacConfigSent;

    Long firstRtpTimestamp() {
        return firstRtpTimestamp;
    }

    void firstRtpTimestamp(Long firstRtpTimestamp) {
        this.firstRtpTimestamp = firstRtpTimestamp;
    }

    Long currentAccessUnitTimestamp() {
        return currentAccessUnitTimestamp;
    }

    void beginAccessUnit(long timestamp) {
        if (currentAccessUnitTimestamp == null || currentAccessUnitTimestamp.longValue() != timestamp) {
            clearAccessUnit();
            currentAccessUnitTimestamp = Long.valueOf(timestamp);
        }
    }

    void addNalToAccessUnit(byte[] nal, boolean keyFrame) {
        if (nal == null || nal.length == 0) {
            return;
        }
        currentAccessUnitNals.add(nal);
        currentAccessUnitKeyFrame = currentAccessUnitKeyFrame || keyFrame;
    }

    List<byte[]> currentAccessUnitNals() {
        return currentAccessUnitNals;
    }

    boolean currentAccessUnitKeyFrame() {
        return currentAccessUnitKeyFrame;
    }

    void clearAccessUnit() {
        currentAccessUnitTimestamp = null;
        currentAccessUnitNals.clear();
        currentAccessUnitKeyFrame = false;
    }

    Long pendingFragmentTimestamp() {
        return pendingFragmentTimestamp;
    }

    int pendingFragmentNalType() {
        return pendingFragmentNalType;
    }

    ByteArrayOutputStream pendingFragmentBuffer() {
        return pendingFragmentBuffer;
    }

    void beginPendingFragment(long timestamp, int nalType, byte[] nalHeader) {
        pendingFragmentTimestamp = Long.valueOf(timestamp);
        pendingFragmentNalType = nalType;
        pendingFragmentBuffer = new ByteArrayOutputStream();
        if (nalHeader != null && nalHeader.length > 0) {
            pendingFragmentBuffer.write(nalHeader, 0, nalHeader.length);
        }
    }

    void appendPendingFragment(byte[] payload, int offset, int length) {
        if (pendingFragmentBuffer == null || payload == null || length <= 0) {
            return;
        }
        pendingFragmentBuffer.write(payload, offset, length);
    }

    byte[] completePendingFragment() {
        if (pendingFragmentBuffer == null) {
            return null;
        }
        byte[] nal = pendingFragmentBuffer.toByteArray();
        clearPendingFragment();
        return nal;
    }

    void clearPendingFragment() {
        pendingFragmentTimestamp = null;
        pendingFragmentNalType = -1;
        pendingFragmentBuffer = null;
    }

    byte[] h264Sps() {
        return h264Sps;
    }

    void h264Sps(byte[] h264Sps) {
        this.h264Sps = h264Sps;
        this.configDirty = true;
    }

    byte[] h264Pps() {
        return h264Pps;
    }

    void h264Pps(byte[] h264Pps) {
        this.h264Pps = h264Pps;
        this.configDirty = true;
    }

    byte[] h265Vps() {
        return h265Vps;
    }

    void h265Vps(byte[] h265Vps) {
        this.h265Vps = h265Vps;
        this.configDirty = true;
    }

    byte[] h265Sps() {
        return h265Sps;
    }

    void h265Sps(byte[] h265Sps) {
        this.h265Sps = h265Sps;
        this.configDirty = true;
    }

    byte[] h265Pps() {
        return h265Pps;
    }

    void h265Pps(byte[] h265Pps) {
        this.h265Pps = h265Pps;
        this.configDirty = true;
    }

    boolean shouldSendConfigBeforeKeyFrame() {
        return !configSent || configDirty;
    }

    void markConfigSent() {
        configSent = true;
        configDirty = false;
    }

    byte[] aacConfig() {
        return aacConfig;
    }

    void aacConfig(byte[] aacConfig) {
        this.aacConfig = aacConfig;
        this.aacConfigDirty = true;
    }

    boolean shouldSendAacConfig() {
        return aacConfig != null && (!aacConfigSent || aacConfigDirty);
    }

    void markAacConfigSent() {
        aacConfigSent = true;
        aacConfigDirty = false;
    }
}
