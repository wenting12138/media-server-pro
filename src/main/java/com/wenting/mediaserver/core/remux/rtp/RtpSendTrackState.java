package com.wenting.mediaserver.core.remux.rtp;

import java.util.concurrent.ThreadLocalRandom;

public final class RtpSendTrackState {

    private int sequenceNumber = ThreadLocalRandom.current().nextInt(0, 65536);
    private long initialRtpTimestamp = ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL;
    private final long ssrc;
    private Long firstDtsMillis;

    public RtpSendTrackState() {
        this(ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL);
    }

    public RtpSendTrackState(long ssrc) {
        this.ssrc = ssrc & 0xFFFFFFFFL;
    }

    public int nextSequenceNumber() {
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        return sequenceNumber;
    }

    public long ssrc() {
        return ssrc;
    }

    public long toRtpTimestamp(int clockRate, Long dtsMillis) {
        long dts = dtsMillis == null ? 0L : dtsMillis.longValue();
        if (firstDtsMillis == null) {
            firstDtsMillis = dts;
        }
        long deltaMillis = Math.max(0L, dts - firstDtsMillis.longValue());
        long deltaTicks = (deltaMillis * Math.max(clockRate, 1)) / 1000L;
        return (initialRtpTimestamp + deltaTicks) & 0xFFFFFFFFL;
    }
}
