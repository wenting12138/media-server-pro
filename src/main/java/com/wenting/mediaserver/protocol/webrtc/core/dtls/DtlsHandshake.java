package com.wenting.mediaserver.protocol.webrtc.core.dtls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.util.Date;
import java.util.Hashtable;
import java.util.concurrent.*;

/**
 * DTLS handshake helper using BouncyCastle.
 *
 * 封装 BouncyCastle 的 DTLS 1.2 握手流程:
 * 1. 创建 DTLS 客户端/服务器
 * 2. 执行握手
 * 3. 导出 SRTP key material (RFC 5764)
 */
public class DtlsHandshake {

    private static final int[] RSA_CIPHER_SUITES = new int[]{
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    };

    private DTLSTransport dtlsTransport;
    private byte[] srtpKeyMaterial;

    /**
     * DTLS handshake with auto-generated self-signed certificate.
     */
    public DtlsHandshake(UdpDatagramTransport transport, boolean isServer) throws IOException {
        this(transport, isServer, null);
    }

    /**
     * DTLS handshake with pre-generated certificate credentials.
     */
    public DtlsHandshake(UdpDatagramTransport transport, boolean isServer,
                          CertCredentials certCredentials) throws IOException {
        SecureRandom secureRandom = new SecureRandom();
        TlsCrypto crypto = new BcTlsCrypto(secureRandom);

        int[] srtpProfiles = new int[]{
            SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80
        };
        UseSRTPData srtpData = new UseSRTPData(srtpProfiles, new byte[0]);

        if (isServer) {
            MyTlsServer serverImpl = new MyTlsServer(crypto, srtpData, certCredentials);
            DTLSServerProtocol protocol = new DTLSServerProtocol();
            this.dtlsTransport = protocol.accept(serverImpl, transport);
            this.srtpKeyMaterial = serverImpl.getSrtpKeyMaterial();
        } else {
            MyTlsClient clientImpl = new MyTlsClient(crypto, srtpData);
            DTLSClientProtocol protocol = new DTLSClientProtocol();
            this.dtlsTransport = protocol.connect(clientImpl, transport);
            this.srtpKeyMaterial = clientImpl.getSrtpKeyMaterial();
        }
    }

    public DTLSTransport getDtlsTransport() {
        return dtlsTransport;
    }

    public byte[] getSrtpKeyMaterial() {
        return srtpKeyMaterial;
    }

    /**
     * 在指定超时内执行 DTLS 握手。超时未完成则抛出 IOException。
     * 防止 BC 原生 DTLS 实现无限阻塞。
     */
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
                future.cancel(true); // 尝试中断
                throw new IOException("DTLS handshake timed out after " + timeoutMs + "ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
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
            try { dtlsTransport.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    // ---- 证书生成 + 指纹工具 ----

    /**
     * Container for pre-generated certificate + private key.
     */
    public static class CertCredentials {
        public final org.bouncycastle.tls.Certificate certificate;
        public final AsymmetricKeyParameter privateKey;

        public CertCredentials(org.bouncycastle.tls.Certificate certificate,
                                AsymmetricKeyParameter privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        TlsCredentialedSigner createSigner(TlsCrypto crypto, TlsContext context) {
            TlsCryptoParameters params = new TlsCryptoParameters(context);
            return new BcDefaultTlsCredentialedSigner(params, (BcTlsCrypto) crypto, privateKey, certificate,
                new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa));
        }
    }

    /**
     * Generate a self-signed RSA-2048 certificate + private key.
     */
    public static CertCredentials generateSelfSignedCert(TlsCrypto crypto) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, crypto.getSecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X500Name name = new X500Name("CN=webrtc-java");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now),
            new Date(now - 86400000L), new Date(now + 365 * 86400000L),
            name, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .build(kp.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(signer);

        BcTlsCertificate tlsCert = new BcTlsCertificate((BcTlsCrypto) crypto, certHolder.getEncoded());
        org.bouncycastle.tls.Certificate bcCert = new org.bouncycastle.tls.Certificate(
            new TlsCertificate[]{ tlsCert });

        AsymmetricKeyParameter bcPrivateKey = PrivateKeyFactory.createKey(kp.getPrivate().getEncoded());
        return new CertCredentials(bcCert, bcPrivateKey);
    }

    /**
     * Compute the SHA-256 fingerprint string (colons-separated uppercase hex) from CertCredentials.
     */
    public static String computeFingerprint(CertCredentials creds) throws Exception {
        byte[] der = creds.certificate.getCertificateAt(0).getEncoded();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(der);
        StringBuilder sb = new StringBuilder(hash.length * 3 - 1);
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", hash[i]));
        }
        return sb.toString();
    }

    // ---- BC DTLS 实现类 ----

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
            return new ProtocolVersion[]{ ProtocolVersion.DTLSv12 };
        }

        @Override
        public int[] getCipherSuites() {
            return RSA_CIPHER_SUITES;
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
                    // 学习项目: 信任所有证书
                }
            };
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            if (tlsContext != null) {
                this.srtpKeyMaterial = tlsContext.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
            }
        }

        byte[] getSrtpKeyMaterial() {
            return srtpKeyMaterial;
        }
    }

    private static class MyTlsServer extends DefaultTlsServer {
        private final UseSRTPData srtpData;
        private final CertCredentials certCredentials;
        private TlsContext tlsContext;
        private volatile byte[] srtpKeyMaterial;

        MyTlsServer(TlsCrypto crypto, UseSRTPData srtpData) {
            this(crypto, srtpData, null);
        }

        MyTlsServer(TlsCrypto crypto, UseSRTPData srtpData, CertCredentials creds) {
            super(crypto);
            this.srtpData = srtpData;
            this.certCredentials = creds;
        }

        @Override
        public void init(TlsServerContext context) {
            super.init(context);
            this.tlsContext = context;
        }

        @Override
        public ProtocolVersion getServerVersion() {
            return ProtocolVersion.DTLSv12;
        }

        @Override
        public int[] getCipherSuites() {
            return RSA_CIPHER_SUITES;
        }

        @Override
        public Hashtable getServerExtensions() throws IOException {
            Hashtable exts = super.getServerExtensions();
            TlsSRTPUtils.addUseSRTPExtension(exts, srtpData);
            return exts;
        }

        @Override
        protected TlsCredentialedSigner getRSASignerCredentials() throws IOException {
            try {
                if (certCredentials != null) {
                    return certCredentials.createSigner(getCrypto(), tlsContext);
                }
                return generateRsaSigner((BcTlsCrypto) getCrypto(), tlsContext);
            } catch (Exception e) {
                throw new IOException("Failed to generate RSA signer credentials", e);
            }
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            if (tlsContext != null) {
                this.srtpKeyMaterial = tlsContext.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
            }
        }

        byte[] getSrtpKeyMaterial() {
            return srtpKeyMaterial;
        }
    }

    // ---- 自签名证书生成 (学习用途) ----

    private static TlsCredentialedSigner generateRsaSigner(BcTlsCrypto crypto, TlsContext context) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, crypto.getSecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X500Name name = new X500Name("CN=webrtc-java");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            new Date(now - 86400000L),
            new Date(now + 365 * 86400000L),
            name,
            kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .build(kp.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(signer);

        BcTlsCertificate tlsCert = new BcTlsCertificate(crypto, certHolder.getEncoded());
        org.bouncycastle.tls.Certificate bcCert = new org.bouncycastle.tls.Certificate(
            new TlsCertificate[]{ tlsCert });

        AsymmetricKeyParameter bcPrivateKey = PrivateKeyFactory.createKey(kp.getPrivate().getEncoded());

        TlsCryptoParameters cryptoParams = new TlsCryptoParameters(context);
        return new BcDefaultTlsCredentialedSigner(cryptoParams, crypto, bcPrivateKey, bcCert,
            new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa));
    }
}
