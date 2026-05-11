package com.wenting.mediaserver.protocol.webrtc.dtls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DtlsClientHelloParserTest {

    @Test
    void shouldParseDtlsClientHello() {
        DtlsClientHello clientHello = new DtlsClientHelloParser().parse(sampleClientHello());

        assertNotNull(clientHello);
        assertEquals(0xFEFD, clientHello.version());
        assertEquals(1, clientHello.cipherSuiteCount());
        assertEquals(32, clientHello.random().length);
    }

    @Test
    void shouldIgnoreNonDtlsPacket() {
        assertNull(new DtlsClientHelloParser().parse(new byte[] {0x00, 0x01, 0x02}));
    }

    public static byte[] sampleClientHello() {
        byte[] packet = new byte[64];
        packet[0] = 22;
        packet[1] = (byte) 0xFE;
        packet[2] = (byte) 0xFD;
        packet[11] = 0x00;
        packet[12] = 0x33;
        packet[13] = 0x01;
        packet[14] = 0x00;
        packet[15] = 0x00;
        packet[16] = 0x27;
        packet[22] = 0x00;
        packet[23] = 0x00;
        packet[24] = 0x27;
        packet[25] = (byte) 0xFE;
        packet[26] = (byte) 0xFD;
        for (int i = 0; i < 32; i++) {
            packet[27 + i] = (byte) (i + 1);
        }
        packet[59] = 0x00;
        packet[60] = 0x00;
        packet[61] = 0x00;
        packet[62] = 0x02;
        packet[63] = 0x2F;
        return packet;
    }
}
