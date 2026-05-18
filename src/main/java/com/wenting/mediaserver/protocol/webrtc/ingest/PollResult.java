package com.wenting.mediaserver.protocol.webrtc.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PollResult {
    public final List<Integer> lostSequenceNumbers;
    public final boolean requestKeyFrameRecovery;

    public PollResult(List<Integer> lostSequenceNumbers, boolean requestKeyFrameRecovery) {
        this.lostSequenceNumbers = lostSequenceNumbers == null
                ? Collections.<Integer>emptyList()
                : Collections.unmodifiableList(new ArrayList<Integer>(lostSequenceNumbers));
        this.requestKeyFrameRecovery = requestKeyFrameRecovery;
    }

    public List<Integer> lostSequenceNumbers() {
        return lostSequenceNumbers;
    }

    public boolean requestKeyFrameRecovery() {
        return requestKeyFrameRecovery;
    }
}
