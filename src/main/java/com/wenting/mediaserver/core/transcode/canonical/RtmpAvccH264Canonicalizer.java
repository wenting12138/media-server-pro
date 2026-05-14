package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.publish.InboundMediaFrame;

import java.util.Arrays;

public final class RtmpAvccH264Canonicalizer implements VideoFrameCanonicalizer {

    private H264CodecConfig latestConfig;

    @Override
    public CanonicalVideoFrame canonicalize(InboundMediaFrame frame) {
        if (frame == null) {
            return null;
        }
        if (frame.configFrame()) {
            latestConfig = parseConfig(frame.payload());
            return new CanonicalVideoFrame(
                    frame,
                    VideoPayloadFormat.H264_AVCC,
                    frame.payload(),
                    true,
                    true,
                    latestConfig
            );
        }
        return new CanonicalVideoFrame(
                frame,
                VideoPayloadFormat.H264_AVCC,
                frame.payload(),
                frame.keyFrame(),
                false,
                latestConfig
        );
    }

    @Override
    public void close() {
        latestConfig = null;
    }

    private H264CodecConfig parseConfig(byte[] payload) {
        if (payload == null || payload.length < 6) {
            return latestConfig;
        }
        int base = 0;
        int end = payload.length;
        int nalLengthSize = (payload[base + 4] & 0x03) + 1;
        int off = base + 5;
        int numSps = payload[off] & 0x1F;
        off++;
        byte[] sps = new byte[0];
        for (int i = 0; i < numSps; i++) {
            if (off + 2 > end) {
                return latestConfig;
            }
            int len = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
            off += 2;
            if (off + len > end) {
                return latestConfig;
            }
            if (i == 0) {
                sps = Arrays.copyOfRange(payload, off, off + len);
            }
            off += len;
        }
        if (off + 1 > end) {
            return latestConfig;
        }
        int numPps = payload[off] & 0xFF;
        off++;
        byte[] pps = new byte[0];
        for (int i = 0; i < numPps; i++) {
            if (off + 2 > end) {
                return latestConfig;
            }
            int len = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
            off += 2;
            if (off + len > end) {
                return latestConfig;
            }
            if (i == 0) {
                pps = Arrays.copyOfRange(payload, off, off + len);
            }
            off += len;
        }
        String profileLevelId = payload.length >= 4
                ? toHex(payload[1]) + toHex(payload[2]) + toHex(payload[3])
                : "";
        return new H264CodecConfig(nalLengthSize, sps, pps, profileLevelId);
    }

    private String toHex(byte value) {
        int unsigned = value & 0xFF;
        String hex = Integer.toHexString(unsigned);
        return hex.length() < 2 ? "0" + hex : hex;
    }
}
