package com.wenting.mediaserver.protocol.webrtc.dtls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcCertificateManagerTest {

    @Test
    void shouldGenerateCertificateAndSha256Fingerprint() {
        WebRtcCertificate certificate = new WebRtcCertificateManager().certificate();

        assertNotNull(certificate);
        assertNotNull(certificate.certificate());
        assertNotNull(certificate.privateKey());
        assertNotNull(certificate.fingerprintSha256());
        assertTrue(certificate.fingerprintSha256().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
    }
}
