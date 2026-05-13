package com.wenting.mediaserver.protocol.webrtc.api;

/**
 * Media stream track (analogous to JS MediaStreamTrack).
 */
public class MediaStreamTrack {

    public enum Kind { AUDIO, VIDEO }

    private final Kind kind;
    private final String id;
    private volatile boolean enabled;

    public MediaStreamTrack(Kind kind, String id) {
        this.kind = kind;
        this.id = id;
        this.enabled = true;
    }

    public Kind getKind() { return kind; }
    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public String toString() {
        return kind + ":" + id + (enabled ? "" : "(disabled)");
    }
}
