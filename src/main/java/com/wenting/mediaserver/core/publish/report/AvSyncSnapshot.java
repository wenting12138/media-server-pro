package com.wenting.mediaserver.core.publish.report;

/**
 * One stream-level A/V sync snapshot mapped onto the sender-report NTP timeline.
 */
public final class AvSyncSnapshot {

    private final String videoTrackId;
    private final String audioTrackId;
    private final long videoNtpMillis;
    private final long audioNtpMillis;
    private final long diffMillis;

    public AvSyncSnapshot(
            String videoTrackId,
            String audioTrackId,
            long videoNtpMillis,
            long audioNtpMillis
    ) {
        this.videoTrackId = videoTrackId == null ? "" : videoTrackId;
        this.audioTrackId = audioTrackId == null ? "" : audioTrackId;
        this.videoNtpMillis = videoNtpMillis;
        this.audioNtpMillis = audioNtpMillis;
        this.diffMillis = audioNtpMillis - videoNtpMillis;
    }

    public String videoTrackId() {
        return videoTrackId;
    }

    public String audioTrackId() {
        return audioTrackId;
    }

    public long videoNtpMillis() {
        return videoNtpMillis;
    }

    public long audioNtpMillis() {
        return audioNtpMillis;
    }

    public long diffMillis() {
        return diffMillis;
    }
}
