package com.wenting.mediaserver.protocol.webrtc.stun;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StunMessageCodecTest {

    @Test
    void shouldDecodeBindingRequestWithUsernamePriorityAndUseCandidate() {
        byte[] transactionId = new byte[] {0x63, 0x41, 0x11, 0x22, 0x01, 0x02, 0x03, 0x04, 0x33, 0x44, 0x55, 0x66};
        byte[] packet = bindingRequest(transactionId, "remote:localufrag", 1845501695L, true);

        StunMessage message = new StunMessageCodec().decode(packet);

        assertNotNull(message);
        assertEquals(StunMessageType.BINDING_REQUEST, message.type());
        assertEquals("remote:localufrag", message.username());
        assertEquals(Long.valueOf(1845501695L), message.priority());
        assertTrue(message.useCandidate());
        assertEquals(transactionId.length, message.transactionId().length);
    }

    @Test
    void shouldEncodeBindingSuccessResponseWithXorMappedAddress() {
        byte[] transactionId = new byte[] {0x63, 0x41, 0x11, 0x22, 0x01, 0x02, 0x03, 0x04, 0x33, 0x44, 0x55, 0x66};
        InetSocketAddress mappedAddress = new InetSocketAddress("192.168.1.10", 54321);

        byte[] response = new StunMessageCodec().encodeBindingSuccessResponse(transactionId, mappedAddress);
        StunMessage message = new StunMessageCodec().decode(response);

        assertNotNull(message);
        assertEquals(StunMessageType.BINDING_SUCCESS_RESPONSE, message.type());
        assertNotNull(message.xorMappedAddress());
        assertEquals("192.168.1.10", message.xorMappedAddress().getAddress().getHostAddress());
        assertEquals(54321, message.xorMappedAddress().getPort());
    }

    private static byte[] bindingRequest(byte[] transactionId, String username, long priority, boolean useCandidate) {
        java.io.ByteArrayOutputStream attributes = new java.io.ByteArrayOutputStream();
        writeAttribute(attributes, 0x0006, username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writeAttribute(attributes, 0x0024, new byte[] {
                (byte) ((priority >> 24) & 0xFF),
                (byte) ((priority >> 16) & 0xFF),
                (byte) ((priority >> 8) & 0xFF),
                (byte) (priority & 0xFF)
        });
        if (useCandidate) {
            writeAttribute(attributes, 0x0025, new byte[0]);
        }
        byte[] attributeBytes = attributes.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeUnsignedShort(out, 0x0001);
        writeUnsignedShort(out, attributeBytes.length);
        out.write(0x21);
        out.write(0x12);
        out.write(0xA4);
        out.write(0x42);
        out.write(transactionId, 0, transactionId.length);
        out.write(attributeBytes, 0, attributeBytes.length);
        return out.toByteArray();
    }

    private static void writeAttribute(java.io.ByteArrayOutputStream out, int type, byte[] value) {
        byte[] safeValue = value == null ? new byte[0] : value;
        writeUnsignedShort(out, type);
        writeUnsignedShort(out, safeValue.length);
        out.write(safeValue, 0, safeValue.length);
        int padding = ((safeValue.length + 3) & ~0x03) - safeValue.length;
        for (int i = 0; i < padding; i++) {
            out.write(0x00);
        }
    }

    private static void writeUnsignedShort(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
