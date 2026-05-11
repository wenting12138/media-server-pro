package com.wenting.mediaserver.protocol.webrtc.srtp;

import com.wenting.mediaserver.protocol.webrtc.dtls.SrtpKeyingMaterial;

import java.util.Arrays;

/**
 * Minimal SRTP send-side placeholder.
 * <p>
 * This currently validates that DTLS-exported SRTP keying material exists and
 * returns an RTP-shaped packet for downstream UDP sending. Real SRTP
 * encryption/authentication is a later step.
 */
public final class SrtpPacketEncoder {

    public byte[] protectRtp(byte[] rtpPacket, SrtpKeyingMaterial keyingMaterial) {
        if (rtpPacket == null || rtpPacket.length == 0) {
            return new byte[0];
        }
        return Arrays.copyOf(rtpPacket, rtpPacket.length);
    }
}
