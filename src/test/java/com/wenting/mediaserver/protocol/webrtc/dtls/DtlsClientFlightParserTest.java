package com.wenting.mediaserver.protocol.webrtc.dtls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DtlsClientFlightParserTest {

    @Test
    void shouldParseClientFlight() {
        DtlsClientFlight clientFlight = new DtlsClientFlightParser().parse(sampleClientFlight());

        assertNotNull(clientFlight);
        assertTrue(clientFlight.hasClientKeyExchange());
        assertTrue(clientFlight.hasChangeCipherSpec());
        assertTrue(clientFlight.hasFinished());
        assertTrue(clientFlight.isCompleteClientFlight());
    }

    @Test
    void shouldIgnoreInvalidPacket() {
        assertNull(new DtlsClientFlightParser().parse(new byte[] {0x01, 0x02}));
    }

    public static byte[] sampleClientFlight() {
        byte[] clientKeyExchange = handshakeRecord(16, new byte[] {0x00, 0x20, 0x01, 0x02, 0x03, 0x04});
        byte[] changeCipherSpec = changeCipherSpecRecord();
        byte[] finished = handshakeRecord(20, new byte[] {0x10, 0x11, 0x12, 0x13, 0x14, 0x15});
        byte[] bytes = new byte[clientKeyExchange.length + changeCipherSpec.length + finished.length];
        System.arraycopy(clientKeyExchange, 0, bytes, 0, clientKeyExchange.length);
        System.arraycopy(changeCipherSpec, 0, bytes, clientKeyExchange.length, changeCipherSpec.length);
        System.arraycopy(finished, 0, bytes, clientKeyExchange.length + changeCipherSpec.length, finished.length);
        return bytes;
    }

    private static byte[] handshakeRecord(int handshakeType, byte[] body) {
        byte[] safeBody = body == null ? new byte[0] : body;
        java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
        handshake.write(handshakeType);
        writeUnsignedMedium(handshake, safeBody.length);
        writeUnsignedShort(handshake, 0x0001);
        writeUnsignedMedium(handshake, 0x000000);
        writeUnsignedMedium(handshake, safeBody.length);
        handshake.write(safeBody, 0, safeBody.length);
        byte[] handshakeBytes = handshake.toByteArray();

        java.io.ByteArrayOutputStream record = new java.io.ByteArrayOutputStream();
        record.write(22);
        writeUnsignedShort(record, 0xFEFD);
        writeUnsignedShort(record, 0x0000);
        writeUnsigned48(record, 0x000000000001L);
        writeUnsignedShort(record, handshakeBytes.length);
        record.write(handshakeBytes, 0, handshakeBytes.length);
        return record.toByteArray();
    }

    private static byte[] changeCipherSpecRecord() {
        java.io.ByteArrayOutputStream record = new java.io.ByteArrayOutputStream();
        record.write(20);
        writeUnsignedShort(record, 0xFEFD);
        writeUnsignedShort(record, 0x0000);
        writeUnsigned48(record, 0x000000000002L);
        writeUnsignedShort(record, 1);
        record.write(0x01);
        return record.toByteArray();
    }

    private static void writeUnsignedShort(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeUnsignedMedium(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeUnsigned48(java.io.ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 40) & 0xFF));
        out.write((int) ((value >> 32) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }
}
