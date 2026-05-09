package com.wenting.mediaserver.core.remux.rtp;

import java.util.Collections;
import java.util.List;

public final class HevcDecoderConfigurationRecord {

    private final int nalLengthSize;
    private final List<byte[]> vpsList;
    private final List<byte[]> spsList;
    private final List<byte[]> ppsList;

    public HevcDecoderConfigurationRecord(int nalLengthSize, List<byte[]> vpsList, List<byte[]> spsList, List<byte[]> ppsList) {
        this.nalLengthSize = nalLengthSize;
        this.vpsList = vpsList == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(vpsList);
        this.spsList = spsList == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(spsList);
        this.ppsList = ppsList == null ? Collections.<byte[]>emptyList() : Collections.unmodifiableList(ppsList);
    }

    public int nalLengthSize() {
        return nalLengthSize;
    }

    public List<byte[]> vpsList() {
        return vpsList;
    }

    public List<byte[]> spsList() {
        return spsList;
    }

    public List<byte[]> ppsList() {
        return ppsList;
    }
}
