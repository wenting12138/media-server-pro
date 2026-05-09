package com.wenting.mediaserver.core.remux.rtp;

public final class AacAudioSpecificConfig {

    private final int sampleRate;
    private final int channels;
    private final String configHex;

    public AacAudioSpecificConfig(int sampleRate, int channels, String configHex) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.configHex = configHex == null ? "" : configHex;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public String configHex() {
        return configHex;
    }
}
