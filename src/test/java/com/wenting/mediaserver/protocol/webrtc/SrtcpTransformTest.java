package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtcpTransform;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpException;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

public class SrtcpTransformTest {

    private static SrtpCryptoContext createContext() {
        SecureRandom rng = new SecureRandom();
        byte[] keyMaterial = new byte[60];
        rng.nextBytes(keyMaterial);
        return SrtpCryptoContext.fromKeyMaterial(keyMaterial, true);
    }

    @Test
    public void shouldProtectAndUnprotectRtcpPacket() throws Exception {
        SrtcpTransform transform = new SrtcpTransform(createContext());
        byte[] rtcp = new byte[]{
                (byte) 0x81, (byte) 201, 0x00, 0x01,
                0x11, 0x22, 0x33, 0x44
        };

        byte[] protectedPacket = transform.protect(rtcp);
        byte[] plain = transform.unprotect(protectedPacket);

        assertArrayEquals(rtcp, plain);
    }

    @Test(expected = SrtpException.class)
    public void shouldRejectTamperedSrtcpPacket() throws Exception {
        SrtcpTransform transform = new SrtcpTransform(createContext());
        byte[] rtcp = new byte[]{
                (byte) 0x81, (byte) 206, 0x00, 0x02,
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66, 0x77, (byte) 0x88
        };

        byte[] protectedPacket = transform.protect(rtcp);
        protectedPacket[protectedPacket.length - 1] ^= 0x01;
        transform.unprotect(protectedPacket);
    }

    @Test
    public void shouldEncryptRtcpBodyButNotHeaderAndSsrc() {
        SrtcpTransform transform = new SrtcpTransform(createContext());
        byte[] rtcp = new byte[]{
                (byte) 0x81, (byte) 206, 0x00, 0x02,
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66, 0x77, (byte) 0x88
        };

        byte[] protectedPacket = transform.protect(rtcp);

        assertArrayEquals(Arrays.copyOfRange(rtcp, 0, 8), Arrays.copyOfRange(protectedPacket, 0, 8));
        assertFalse(Arrays.equals(Arrays.copyOfRange(rtcp, 8, 12), Arrays.copyOfRange(protectedPacket, 8, 12)));
    }
}
