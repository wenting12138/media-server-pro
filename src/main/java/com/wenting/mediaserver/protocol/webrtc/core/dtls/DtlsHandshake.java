package com.wenting.mediaserver.protocol.webrtc.core.dtls;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Hashtable;
import java.util.concurrent.*;

/**
 * DTLS handshake helper using BouncyCastle.
 *
 * Uses the same negotiation path as the legacy WebRtcBcDtlsEngine:
 * - JcaTlsCrypto (BC provider)
 * - RSA ECDHE cipher suites
 * - mandatory use_srtp profile: SRTP_AES128_CM_HMAC_SHA1_80
 */
public class DtlsHandshake {

    private static final Logger LOG = LoggerFactory.getLogger(DtlsHandshake.class);
    private static final int DTLS_SRTP_EXPORTER_LEN = 2 * (16 + 14);

    private static final int[] WEBRTC_CIPHER_SUITES = new int[]{
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    };

    private DTLSTransport dtlsTransport;
    private byte[] srtpKeyMaterial;

    public DtlsHandshake(UdpDatagramTransport transport, boolean isServer) throws IOException {
        this(transport, isServer, null);
    }

    public DtlsHandshake(UdpDatagramTransport transport, boolean isServer,
                         CertCredentials certCredentials) throws IOException {
        installBouncyCastleProvider();

        TlsCrypto crypto;
        try {
            crypto = new JcaTlsCryptoProvider().setProvider("BC").create(new SecureRandom());
        } catch (Exception e) {
            throw new IOException("Failed to create JCA TLS crypto", e);
        }

        UseSRTPData srtpData = new UseSRTPData(
            new int[]{SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80},
            new byte[0]);

        LOG.info("DTLS handshake begin role={}", isServer ? "server" : "client");

        if (isServer) {
            CertCredentials credentials = certCredentials != null
                ? certCredentials
                : safeGenerateCredentials(crypto);
            MyTlsServer server = new MyTlsServer((JcaTlsCrypto) crypto,
                credentials.privateKey, credentials.certificate);
            DTLSServerProtocol protocol = new DTLSServerProtocol();
            this.dtlsTransport = protocol.accept(server, transport);
            this.srtpKeyMaterial = server.getSrtpKeyMaterial();
            LOG.info("DTLS handshake finished role=server");
        } else {
            MyTlsClient client = new MyTlsClient(crypto, srtpData);
            DTLSClientProtocol protocol = new DTLSClientProtocol();
            this.dtlsTransport = protocol.connect(client, transport);
            this.srtpKeyMaterial = client.getSrtpKeyMaterial();
            LOG.info("DTLS handshake finished role=client");
        }
    }

    public DTLSTransport getDtlsTransport() {
        return dtlsTransport;
    }

    public byte[] getSrtpKeyMaterial() {
        return srtpKeyMaterial;
    }

    public static DtlsHandshake handshakeWithTimeout(UdpDatagramTransport transport,
                                                     boolean isServer,
                                                     CertCredentials certCredentials,
                                                     long timeoutMs) throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dtls-handshake-timeout");
            t.setDaemon(true);
            return t;
        });

        try {
            Future<DtlsHandshake> future = executor.submit(
                () -> new DtlsHandshake(transport, isServer, certCredentials));

            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("DTLS handshake timed out after " + timeoutMs + "ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("DTLS handshake failed: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("DTLS handshake interrupted");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    public void close() {
        if (dtlsTransport != null) {
            try {
                dtlsTransport.close();
            } catch (IOException ignore) {
                // ignore
            }
        }
    }

    private static CertCredentials safeGenerateCredentials(TlsCrypto crypto) throws IOException {
        try {
            return generateSelfSignedCert(crypto);
        } catch (Exception e) {
            throw new IOException("Failed to generate self-signed DTLS certificate", e);
        }
    }

    private static void installBouncyCastleProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static class CertCredentials {
        public final org.bouncycastle.tls.Certificate certificate;
        public final PrivateKey privateKey;

        public CertCredentials(org.bouncycastle.tls.Certificate certificate,
                               PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        TlsCredentialedSigner createSigner(TlsCrypto crypto, TlsContext context) {
            SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(
                HashAlgorithm.sha256, SignatureAlgorithm.rsa);
            return new JcaDefaultTlsCredentialedSigner(
                new TlsCryptoParameters(context),
                (JcaTlsCrypto) crypto,
                privateKey,
                certificate,
                sigAlg);
        }
    }

    public static CertCredentials generateSelfSignedCert(TlsCrypto crypto) throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("media-server-webrtc");
        try {
            JcaTlsCrypto jcaCrypto = (JcaTlsCrypto) crypto;
            TlsCertificate tlsCert = jcaCrypto.createCertificate(cert.cert().getEncoded());
            org.bouncycastle.tls.Certificate chain = new org.bouncycastle.tls.Certificate(
                new TlsCertificate[]{tlsCert});
            return new CertCredentials(chain, cert.key());
        } finally {
            cert.delete();
        }
    }

    public static String computeFingerprint(CertCredentials creds) throws Exception {
        byte[] der = creds.certificate.getCertificateAt(0).getEncoded();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(der);
        StringBuilder sb = new StringBuilder(hash.length * 3 - 1);
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02X", hash[i]));
        }
        return sb.toString();
    }

    private static class MyTlsClient extends DefaultTlsClient {
        private final UseSRTPData srtpData;
        private TlsContext tlsContext;
        private volatile byte[] srtpKeyMaterial;

        MyTlsClient(TlsCrypto crypto, UseSRTPData srtpData) {
            super(crypto);
            this.srtpData = srtpData;
        }

        @Override
        public void init(TlsClientContext context) {
            super.init(context);
            this.tlsContext = context;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            return new ProtocolVersion[]{ProtocolVersion.DTLSv12};
        }

        @Override
        public int[] getCipherSuites() {
            return WEBRTC_CIPHER_SUITES;
        }

        @Override
        public Hashtable getClientExtensions() throws IOException {
            Hashtable exts = super.getClientExtensions();
            TlsSRTPUtils.addUseSRTPExtension(exts, srtpData);
            return exts;
        }

        @Override
        public TlsAuthentication getAuthentication() {
            return new ServerOnlyTlsAuthentication() {
                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCert) {
                    // Fingerprint validation is handled by signaling.
                }
            };
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            if (tlsContext != null) {
                this.srtpKeyMaterial = tlsContext.exportKeyingMaterial(
                    "EXTRACTOR-dtls_srtp", null, 60);
            }
            LOG.info("DTLS client handshake complete");
        }

        byte[] getSrtpKeyMaterial() {
            return srtpKeyMaterial;
        }
    }

    private static final class MyTlsServer extends DefaultTlsServer {
        private final JcaTlsCrypto crypto;
        private final PrivateKey privateKey;
        private final org.bouncycastle.tls.Certificate certificate;
        private boolean clientOfferedSrtpProfile;
        private volatile byte[] srtpKeyMaterial;

        MyTlsServer(JcaTlsCrypto crypto, PrivateKey privateKey,
                    org.bouncycastle.tls.Certificate certificate) {
            super(crypto);
            this.crypto = crypto;
            this.privateKey = privateKey;
            this.certificate = certificate;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            return new ProtocolVersion[]{ProtocolVersion.DTLSv12};
        }

        @Override
        protected int[] getSupportedCipherSuites() {
            return WEBRTC_CIPHER_SUITES;
        }

        @Override
        public void processClientExtensions(Hashtable clientExtensions) throws IOException {
            super.processClientExtensions(clientExtensions);
            UseSRTPData offered = TlsSRTPUtils.getUseSRTPExtension(clientExtensions);
            clientOfferedSrtpProfile = hasSrtpAes128Sha1_80(offered);
            if (!clientOfferedSrtpProfile) {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter,
                    "client did not offer required use_srtp profile");
            }
        }

        @Override
        public Hashtable getServerExtensions() throws IOException {
            Hashtable extensions = super.getServerExtensions();
            if (extensions == null) {
                extensions = new Hashtable();
            }
            if (!clientOfferedSrtpProfile) {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter,
                    "use_srtp not negotiated");
            }
            TlsSRTPUtils.addUseSRTPExtension(
                extensions,
                new UseSRTPData(
                    new int[]{SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80},
                    new byte[0]));
            return extensions;
        }

        @Override
        protected TlsCredentialedSigner getRSASignerCredentials() {
            SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(
                HashAlgorithm.sha256, SignatureAlgorithm.rsa);
            return new JcaDefaultTlsCredentialedSigner(
                new TlsCryptoParameters(context),
                crypto,
                privateKey,
                certificate,
                sigAlg);
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            this.srtpKeyMaterial = context.exportKeyingMaterial(
                ExporterLabel.dtls_srtp, null, DTLS_SRTP_EXPORTER_LEN);
            LOG.info("DTLS server handshake complete");
        }

        @Override
        public void notifyAlertReceived(short alertLevel, short alertDescription) {
            LOG.warn("DTLS server alert received level={} desc={}", alertLevel, alertDescription);
        }

        byte[] getSrtpKeyMaterial() {
            return srtpKeyMaterial;
        }

        private static boolean hasSrtpAes128Sha1_80(UseSRTPData srtp) {
            if (srtp == null || srtp.getProtectionProfiles() == null) {
                return false;
            }
            int[] profiles = srtp.getProtectionProfiles();
            for (int profile : profiles) {
                if (profile == SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80) {
                    return true;
                }
            }
            return false;
        }
    }
}
