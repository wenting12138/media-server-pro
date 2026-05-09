package com.wenting.mediaserver.core.remux.rtp;

import java.util.Collections;
import java.util.List;

public final class AvcDecoderConfigurationRecord {

    private final int nalLengthSize;
    private final String profileLevelIdHex;
    private final List<byte[]> spsList;
    private final List<byte[]> ppsList;

    public AvcDecoderConfigurationRecord(int nalLengthSize, String profileLevelIdHex, List<byte[]> spsList, List<byte[]> ppsList) {
        this.nalLengthSize = nalLengthSize;
        this.profileLevelIdHex = profileLevelIdHex == null ? "" : profileLevelIdHex;
        this.spsList = spsList == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(spsList);
        this.ppsList = ppsList == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(ppsList);
    }

    public int nalLengthSize() {
        return nalLengthSize;
    }

    public String profileLevelIdHex() {
        return profileLevelIdHex;
    }

    public List<byte[]> spsList() {
        return spsList;
    }

    public List<byte[]> ppsList() {
        return ppsList;
    }
}
