package com.wenting.mediaserver.protocol.webrtc.dtls;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtlsServerTransportTest {

    @Test
    void shouldGenerateServerHelloFlightAndExportSrtpKeysAfterClientHello() {
        DtlsServerTransport transport = new DtlsServerTransport(
                "peer-1",
                new WebRtcCertificateManager().certificate()
        );

        byte[] serverHelloFlight = transport.handleClientHello(
                DtlsClientHelloParserTest.sampleClientHello(),
                new InetSocketAddress("10.0.0.30", 55000)
        );

        assertNotNull(serverHelloFlight);
        assertTrue(serverHelloFlight.length > 0);
        assertEquals(22, serverHelloFlight[0] & 0xFF);
        assertEquals(2, serverHelloFlight[13] & 0xFF);
        assertTrue(containsHandshakeType(serverHelloFlight, 11));
        assertTrue(containsHandshakeType(serverHelloFlight, 14));
        assertEquals(DtlsTransportState.SERVER_HELLO_PREPARED, transport.state());
        assertEquals(32, transport.clientRandom().length);
        assertEquals(32, transport.serverRandom().length);
        assertTrue(transport.handshakeTranscript().length > transport.clientRandom().length);
        assertNotNull(transport.srtpKeyingMaterial());
        assertTrue(transport.srtpKeyingMaterial().raw().length > 0);

        transport.markServerHelloSent();

        assertEquals(DtlsTransportState.SRTP_KEYING_EXPORTED, transport.state());
    }

    private static boolean containsHandshakeType(byte[] bytes, int handshakeType) {
        if (bytes == null || bytes.length < 14) {
            return false;
        }
        for (int i = 0; i < bytes.length - 13; i++) {
            if ((bytes[i] & 0xFF) == 22 && (bytes[i + 13] & 0xFF) == handshakeType) {
                return true;
            }
        }
        return false;
    }
}
