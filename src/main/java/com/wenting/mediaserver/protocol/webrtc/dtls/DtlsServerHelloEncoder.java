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
        writeUnsignedShort(body, DTLS_VERSION);
        body.write(safeServerRandom, 0, Math.min(32, safeServerRandom.length));
        if (safeServerRandom.length < 32) {
            for (int i = safeServerRandom.length; i < 32; i++) {
                body.write(0x00);
            }
        }
        body.write(0x00);
        writeUnsignedShort(body, CIPHER_SUITE_TLS_RSA_WITH_AES_128_CBC_SHA);
        body.write(0x00);
        writeUnsignedShort(body, 0x0000);
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(HANDSHAKE_TYPE_SERVER_HELLO);
        writeUnsignedMedium(handshake, bodyBytes.length);
        writeUnsignedShort(handshake, 0x0000);
        writeUnsignedMedium(handshake, 0x000000);
        writeUnsignedMedium(handshake, bodyBytes.length);
        handshake.write(bodyBytes, 0, bodyBytes.length);
        byte[] handshakeBytes = handshake.toByteArray();

        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(CONTENT_TYPE_HANDSHAKE);
        writeUnsignedShort(record, DTLS_VERSION);
        writeUnsignedShort(record, 0x0000);
        writeUnsigned48(record, 0x000000000000L);
        writeUnsignedShort(record, handshakeBytes.length);
        record.write(handshakeBytes, 0, handshakeBytes.length);
        return record.toByteArray();
    }

    private static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeUnsignedMedium(ByteArrayOutputStream output, int value) {
        output.write((value >> 16) & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeUnsigned48(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >> 40) & 0xFF));
        output.write((int) ((value >> 32) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }
}
