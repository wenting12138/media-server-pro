package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.io.ByteArrayOutputStream;
import java.security.cert.CertificateEncodingException;

public final class DtlsCertificateEncoder {

    private static final int HANDSHAKE_TYPE_CERTIFICATE = 11;

    public byte[] encode(WebRtcCertificate certificate) {
        if (certificate == null || certificate.certificate() == null) {
            return new byte[0];
        }
        try {
            byte[] encodedCertificate = certificate.certificate().getEncoded();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int certificateEntryLength = encodedCertificate.length + 3;
            DtlsHandshakeEncoderSupport.writeUnsignedMedium(body, certificateEntryLength);
            DtlsHandshakeEncoderSupport.writeUnsignedMedium(body, encodedCertificate.length);
            body.write(encodedCertificate, 0, encodedCertificate.length);
            return DtlsHandshakeEncoderSupport.encodeHandshakeRecord(HANDSHAKE_TYPE_CERTIFICATE, body.toByteArray());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Failed to encode DTLS certificate", e);
        }
    }
}
