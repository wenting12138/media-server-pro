package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public final class WebRtcCertificate {

    private final X509Certificate certificate;
    private final PrivateKey privateKey;
    private final String fingerprintSha256;

    public WebRtcCertificate(X509Certificate certificate, PrivateKey privateKey, String fingerprintSha256) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.fingerprintSha256 = fingerprintSha256;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public String fingerprintSha256() {
        return fingerprintSha256;
    }
}
