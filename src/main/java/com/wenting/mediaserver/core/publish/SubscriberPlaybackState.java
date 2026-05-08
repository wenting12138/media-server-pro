package com.wenting.mediaserver.core.publish;

/**
 * Per-subscriber playback gate state for live start behavior.
 */
public final class SubscriberPlaybackState {

    private boolean started;
    private boolean waitingForKeyFrame = true;

    public boolean started() {
        return started;
    }

    public boolean waitingForKeyFrame() {
        return waitingForKeyFrame;
    }

    public void start() {
        this.started = true;
        this.waitingForKeyFrame = false;
    }

    public void waitForKeyFrame() {
        this.started = false;
        this.waitingForKeyFrame = true;
    }
}
