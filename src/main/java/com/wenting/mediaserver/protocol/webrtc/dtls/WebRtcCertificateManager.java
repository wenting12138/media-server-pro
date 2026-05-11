package com.wenting.mediaserver.protocol.webrtc.dtls;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Locale;

public final class WebRtcCertificateManager {

    private final WebRtcCertificate certificate;

    public WebRtcCertificateManager() {
        this.certificate = generate();
    }

    public WebRtcCertificate certificate() {
        return certificate;
    }

    private static WebRtcCertificate generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();
            X509Certificate certificate = selfSign(keyPair);
            String fingerprint = fingerprintSha256(certificate);
            return new WebRtcCertificate(certificate, keyPair.getPrivate(), fingerprint);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate WebRTC certificate", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build self-signed WebRTC certificate", e);
        }
    }

    private static X509Certificate selfSign(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 60_000L);
        Date notAfter = new Date(now + 3650L * 24L * 60L * 60L * 1000L);
        BigInteger serialNumber = new BigInteger(64, new SecureRandom()).abs();
        X500Name owner = new X500Name("CN=media-server-webrtc");

        X509CertInfo certInfo = new X509CertInfo();
        certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(serialNumber));
        certInfo.set(X509CertInfo.SUBJECT, owner);
        certInfo.set(X509CertInfo.ISSUER, owner);
        certInfo.set(X509CertInfo.VALIDITY, new CertificateValidity(notBefore, notAfter));
        certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic()));
        AlgorithmId algorithmId = AlgorithmId.get("SHA256withRSA");
        certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithmId));

        X509CertImpl certificate = new X509CertImpl(certInfo);
        PrivateKey privateKey = keyPair.getPrivate();
        certificate.sign(privateKey, "SHA256withRSA");
        AlgorithmId signedAlgorithmId = (AlgorithmId) certificate.get(X509CertImpl.SIG_ALG);
        certInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, signedAlgorithmId);
        certificate = new X509CertImpl(certInfo);
        certificate.sign(privateKey, "SHA256withRSA");
        return certificate;
    }

    private static String fingerprintSha256(X509Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder builder = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            int value = digest[i] & 0xFF;
            if (value < 0x10) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
