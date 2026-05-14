package com.wenting.mediaserver.core.transcode.canonical;

import java.util.Arrays;

public final class H264CodecConfig {

    private final int nalLengthSize;
    private final byte[] sps;
    private final byte[] pps;
    private final String profileLevelId;

    public H264CodecConfig(int nalLengthSize, byte[] sps, byte[] pps, String profileLevelId) {
        this.nalLengthSize = nalLengthSize <= 0 ? 4 : nalLengthSize;
        this.sps = sps == null ? new byte[0] : Arrays.copyOf(sps, sps.length);
        this.pps = pps == null ? new byte[0] : Arrays.copyOf(pps, pps.length);
        this.profileLevelId = profileLevelId == null ? "" : profileLevelId;
    }

    public int nalLengthSize() {
        return nalLengthSize;
    }

    public byte[] sps() {
        return Arrays.copyOf(sps, sps.length);
    }

    public byte[] pps() {
        return Arrays.copyOf(pps, pps.length);
    }

    public String profileLevelId() {
        return profileLevelId;
    }

    public boolean complete() {
        return sps.length > 0 && pps.length > 0;
    }
}
