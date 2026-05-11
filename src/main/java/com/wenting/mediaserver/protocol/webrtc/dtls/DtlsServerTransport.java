package com.wenting.mediaserver.protocol.webrtc.dtls;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class DtlsServerTransport {

    private static final String EXPORTER_LABEL = "EXTRACTOR-dtls_srtp";

    private final String sessionId;
    private final WebRtcCertificate certificate;
    private final DtlsClientHelloParser clientHelloParser = new DtlsClientHelloParser();
    private final DtlsServerHelloEncoder serverHelloEncoder = new DtlsServerHelloEncoder();
    private final DtlsCertificateEncoder certificateEncoder = new DtlsCertificateEncoder();
    private final DtlsServerHelloDoneEncoder serverHelloDoneEncoder = new DtlsServerHelloDoneEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    private volatile DtlsTransportState state = DtlsTransportState.NEW;
    private volatile InetSocketAddress remoteAddress;
    private volatile byte[] clientRandom = new byte[0];
    private volatile byte[] serverRandom = new byte[0];
    private volatile byte[] lastClientHello = new byte[0];
    private volatile byte[] handshakeTranscript = new byte[0];
    private volatile byte[] lastServerHelloFlight = new byte[0];
    private volatile SrtpKeyingMaterial srtpKeyingMaterial;

    public DtlsServerTransport(String sessionId, WebRtcCertificate certificate) {
        this.sessionId = sessionId;
        this.certificate = certificate;
    }

    public DtlsTransportState state() {
        return state;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public byte[] clientRandom() {
        return copy(clientRandom);
    }

    public byte[] serverRandom() {
        return copy(serverRandom);
    }

    public SrtpKeyingMaterial srtpKeyingMaterial() {
        return srtpKeyingMaterial;
    }

    public byte[] lastClientHello() {
        return copy(lastClientHello);
    }

    public byte[] lastServerHelloFlight() {
        return copy(lastServerHelloFlight);
    }

    public byte[] handshakeTranscript() {
        return copy(handshakeTranscript);
    }

    public boolean looksLikeClientHello(byte[] packet) {
        return clientHelloParser.looksLikeClientHello(packet);
    }

    public byte[] handleClientHello(byte[] packet, InetSocketAddress remoteAddress) {
        DtlsClientHello clientHello = clientHelloParser.parse(packet);
        if (clientHello == null) {
            return null;
        }
        this.remoteAddress = remoteAddress;
        this.lastClientHello = copy(packet);
        this.clientRandom = clientHello.random();
        if (serverRandom.length == 0) {
            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            this.serverRandom = randomBytes;
        }
        this.state = DtlsTransportState.CLIENT_HELLO_RECEIVED;
        byte[] serverHello = serverHelloEncoder.encode(serverRandom);
        byte[] certificateMessage = certificateEncoder.encode(certificate);
        byte[] serverHelloDone = serverHelloDoneEncoder.encode();
        this.lastServerHelloFlight = concatenate(serverHello, certificateMessage, serverHelloDone);
        this.handshakeTranscript = concatenate(this.lastClientHello, this.lastServerHelloFlight);
        this.state = DtlsTransportState.SERVER_HELLO_PREPARED;
        this.srtpKeyingMaterial = exportSrtpKeyingMaterial();
        return copy(lastServerHelloFlight);
    }

    public void markServerHelloSent() {
        if (lastServerHelloFlight.length == 0) {
            return;
        }
        this.state = DtlsTransportState.SERVER_HELLO_SENT;
        if (srtpKeyingMaterial != null && srtpKeyingMaterial.raw().length > 0) {
            this.state = DtlsTransportState.SRTP_KEYING_EXPORTED;
        }
    }

    public SrtpKeyingMaterial exportSrtpKeyingMaterial() {
        byte[] keyingMaterialBytes = deriveKeyingMaterial(SrtpKeyingMaterial.TOTAL_LENGTH);
        if (keyingMaterialBytes.length < SrtpKeyingMaterial.TOTAL_LENGTH) {
            return new SrtpKeyingMaterial(new byte[0], new byte[0], new byte[0], new byte[0], keyingMaterialBytes);
        }
        byte[] clientWriteKey = Arrays.copyOfRange(keyingMaterialBytes, 0, SrtpKeyingMaterial.KEY_LENGTH);
        byte[] serverWriteKey = Arrays.copyOfRange(keyingMaterialBytes,
                SrtpKeyingMaterial.KEY_LENGTH,
                SrtpKeyingMaterial.KEY_LENGTH * 2);
        byte[] clientWriteSalt = Arrays.copyOfRange(keyingMaterialBytes,
                SrtpKeyingMaterial.KEY_LENGTH * 2,
                SrtpKeyingMaterial.KEY_LENGTH * 2 + SrtpKeyingMaterial.SALT_LENGTH);
        byte[] serverWriteSalt = Arrays.copyOfRange(keyingMaterialBytes,
                SrtpKeyingMaterial.KEY_LENGTH * 2 + SrtpKeyingMaterial.SALT_LENGTH,
                keyingMaterialBytes.length);
        return new SrtpKeyingMaterial(clientWriteKey, serverWriteKey, clientWriteSalt, serverWriteSalt, keyingMaterialBytes);
    }

    private byte[] deriveKeyingMaterial(int length) {
        if (clientRandom.length == 0 || serverRandom.length == 0 || certificate == null || certificate.privateKey() == null) {
            return new byte[0];
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sessionId.getBytes(StandardCharsets.UTF_8));
            digest.update(certificate.privateKey().getEncoded());
            digest.update(handshakeTranscript);
            byte[] secret = digest.digest(certificate.certificate().getEncoded());
            byte[] seed = concatenate(
                    EXPORTER_LABEL.getBytes(StandardCharsets.US_ASCII),
                    clientRandom,
                    serverRandom,
                    handshakeTranscript
            );
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] output = new byte[length];
            byte[] previous = seed;
            int offset = 0;
            while (offset < output.length) {
                previous = mac.doFinal(previous);
                int copyLength = Math.min(previous.length, output.length - offset);
                System.arraycopy(previous, 0, output, offset, copyLength);
                offset += copyLength;
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive SRTP keying material", e);
        }
    }

    private static byte[] concatenate(byte[]... parts) {
        int totalLength = 0;
        for (byte[] part : parts) {
            totalLength += part == null ? 0 : part.length;
        }
        byte[] bytes = new byte[totalLength];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null || part.length == 0) {
                continue;
            }
            System.arraycopy(part, 0, bytes, offset, part.length);
            offset += part.length;
        }
        return bytes;
    }

    private static byte[] copy(byte[] value) {
        return value == null ? new byte[0] : Arrays.copyOf(value, value.length);
    }
}
