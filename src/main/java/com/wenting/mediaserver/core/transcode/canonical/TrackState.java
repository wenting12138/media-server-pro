package com.wenting.mediaserver.core.transcode.canonical;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class TrackState {
    public Long firstRtpTimestamp;
    public Long currentAccessUnitTimestamp;
    public final List<byte[]> currentAccessUnitNals = new ArrayList<byte[]>();
    public boolean currentAccessUnitKeyFrame;
    public Long pendingFragmentTimestamp;
    public ByteArrayOutputStream pendingFragmentBuffer;
    public byte[] sps;
    public byte[] pps;
    public boolean configDirty = true;
    public boolean configSent;
    public void beginAccessUnit(long timestamp) {
        if (currentAccessUnitTimestamp == null || currentAccessUnitTimestamp.longValue() != timestamp) {
            clearAccessUnit();
            currentAccessUnitTimestamp = Long.valueOf(timestamp);
        }
    }
    public void addNalToAccessUnit(byte[] nal, boolean keyFrame) {
        if (nal == null || nal.length == 0) {
            return;
        }
        currentAccessUnitNals.add(nal);
        currentAccessUnitKeyFrame = currentAccessUnitKeyFrame || keyFrame;
    }
    public void beginPendingFragment(long timestamp, byte[] nalHeader) {
        pendingFragmentTimestamp = Long.valueOf(timestamp);
        pendingFragmentBuffer = new ByteArrayOutputStream();
        if (nalHeader != null && nalHeader.length > 0) {
            pendingFragmentBuffer.write(nalHeader, 0, nalHeader.length);
        }
    }
    public byte[] completePendingFragment() {
        if (pendingFragmentBuffer == null) {
            return null;
        }
        byte[] nal = pendingFragmentBuffer.toByteArray();
        clearPendingFragment();
        return nal;
    }
    public void clearPendingFragment() {
        pendingFragmentTimestamp = null;
        pendingFragmentBuffer = null;
    }
    public boolean shouldSendConfigBeforeKeyFrame() {
        return !configSent || configDirty;
    }
    public void markConfigSent() {
        configSent = true;
        configDirty = false;
    }
    public void clearAccessUnit() {
        currentAccessUnitTimestamp = null;
        currentAccessUnitNals.clear();
        currentAccessUnitKeyFrame = false;
    }
}