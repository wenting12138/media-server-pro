package com.wenting.mediaserver.core.remux.rtp;

public final class AacAudioSpecificConfigParser {

    private static final int[] SAMPLE_RATES = new int[]{
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    public AacAudioSpecificConfig parse(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        int byte0 = payload[0] & 0xFF;
        int byte1 = payload[1] & 0xFF;
        int samplingFrequencyIndex = ((byte0 & 0x07) << 1) | ((byte1 >> 7) & 0x01);
        int channelConfiguration = (byte1 >> 3) & 0x0F;
        int sampleRate = samplingFrequencyIndex >= 0 && samplingFrequencyIndex < SAMPLE_RATES.length
                ? SAMPLE_RATES[samplingFrequencyIndex]
                : 0;
        return new AacAudioSpecificConfig(sampleRate, channelConfiguration, toHex(payload));
    }

    private static String toHex(byte[] payload) {
        StringBuilder builder = new StringBuilder(payload.length * 2);
        for (byte b : payload) {
            builder.append(String.format("%02X", b & 0xFF));
        }
        return builder.toString();
    }
}
