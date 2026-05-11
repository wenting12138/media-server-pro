package com.wenting.mediaserver.protocol.webrtc.srtp;

import com.wenting.mediaserver.protocol.webrtc.dtls.SrtpKeyingMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SrtpPacketEncoderTest {

    @Test
    void shouldProtectRtpPayloadAndAppendAuthTag() {
        SrtpPacketEncoder encoder = new SrtpPacketEncoder();
        byte[] rtpPacket = new byte[] {
                (byte) 0x80, (byte) 0xE1, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22, 0x33, 0x44
        };

        byte[] protectedPacket = encoder.protectRtp(rtpPacket, sampleKeyingMaterial());

        assertEquals(rtpPacket.length + 10, protectedPacket.length);
        assertArrayEquals(slice(rtpPacket, 0, 12), slice(protectedPacket, 0, 12));
        assertFalse(arrayEquals(slice(rtpPacket, 12, rtpPacket.length), slice(protectedPacket, 12, rtpPacket.length)));
    }

    @Test
    void shouldProduceDifferentAuthForDifferentSequenceNumbers() {
        SrtpPacketEncoder encoder = new SrtpPacketEncoder();

        byte[] packet1 = new byte[] {
                (byte) 0x80, (byte) 0xE1, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        };
        byte[] packet2 = new byte[] {
                (byte) 0x80, (byte) 0xE1, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        };

        byte[] protected1 = encoder.protectRtp(packet1, sampleKeyingMaterial());
        byte[] protected2 = encoder.protectRtp(packet2, sampleKeyingMaterial());

        assertFalse(arrayEquals(protected1, protected2));
    }

    private static SrtpKeyingMaterial sampleKeyingMaterial() {
        byte[] clientWriteKey = new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
        };
        byte[] serverWriteKey = new byte[] {
                0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20
        };
        byte[] clientWriteSalt = new byte[] {
                0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
                0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E
        };
        byte[] serverWriteSalt = new byte[] {
                0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
                0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E
        };
        byte[] raw = new byte[60];
        return new SrtpKeyingMaterial(clientWriteKey, serverWriteKey, clientWriteSalt, serverWriteSalt, raw);
    }

    private static byte[] slice(byte[] bytes, int startInclusive, int endExclusive) {
        byte[] slice = new byte[endExclusive - startInclusive];
        System.arraycopy(bytes, startInclusive, slice, 0, slice.length);
        return slice;
    }

    private static boolean arrayEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        return true;
    }
}
