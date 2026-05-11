package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.io.ByteArrayOutputStream;

public final class DtlsServerHelloEncoder {

    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int HANDSHAKE_TYPE_SERVER_HELLO = 2;
    private static final int DTLS_VERSION = 0xFEFD;
    private static final int CIPHER_SUITE_TLS_RSA_WITH_AES_128_CBC_SHA = 0x002F;

    public byte[] encode(byte[] serverRandom) {
        byte[] safeServerRandom = serverRandom == null ? new byte[32] : serverRandom;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DtlsHandshakeEncoderSupport.writeUnsignedShort(body, DTLS_VERSION);
        body.write(safeServerRandom, 0, Math.min(32, safeServerRandom.length));
        if (safeServerRandom.length < 32) {
            for (int i = safeServerRandom.length; i < 32; i++) {
                body.write(0x00);
            }
        }
        body.write(0x00);
        DtlsHandshakeEncoderSupport.writeUnsignedShort(body, CIPHER_SUITE_TLS_RSA_WITH_AES_128_CBC_SHA);
        body.write(0x00);
        DtlsHandshakeEncoderSupport.writeUnsignedShort(body, 0x0000);
        byte[] bodyBytes = body.toByteArray();

        return DtlsHandshakeEncoderSupport.encodeHandshakeRecord(HANDSHAKE_TYPE_SERVER_HELLO, bodyBytes);
    }
}
