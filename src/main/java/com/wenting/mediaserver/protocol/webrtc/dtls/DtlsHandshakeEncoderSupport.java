package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.io.ByteArrayOutputStream;

final class DtlsHandshakeEncoderSupport {

    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int DTLS_VERSION = 0xFEFD;

    private DtlsHandshakeEncoderSupport() {
    }

    static byte[] encodeHandshakeRecord(int handshakeType, byte[] bodyBytes) {
        byte[] safeBody = bodyBytes == null ? new byte[0] : bodyBytes;

        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(handshakeType);
        writeUnsignedMedium(handshake, safeBody.length);
        writeUnsignedShort(handshake, 0x0000);
        writeUnsignedMedium(handshake, 0x000000);
        writeUnsignedMedium(handshake, safeBody.length);
        handshake.write(safeBody, 0, safeBody.length);
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

    static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    static void writeUnsignedMedium(ByteArrayOutputStream output, int value) {
        output.write((value >> 16) & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    static void writeUnsigned48(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >> 40) & 0xFF));
        output.write((int) ((value >> 32) & 0xFF));
        output.write((int) ((value >> 24) & 0xFF));
        output.write((int) ((value >> 16) & 0xFF));
        output.write((int) ((value >> 8) & 0xFF));
        output.write((int) (value & 0xFF));
    }
}
