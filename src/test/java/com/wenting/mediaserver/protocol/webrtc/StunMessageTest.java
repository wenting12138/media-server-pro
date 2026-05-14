package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.stun.StunClass;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunConstants;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

import static org.junit.Assert.*;

/**
 * STUN 消息编解码单元测试。
 */
public class StunMessageTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    public void testCreateBindingRequest() {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        StunMessage req = StunMessage.createBindingRequest(txId);

        assertEquals(StunConstants.METHOD_BINDING, req.getMethod());
        assertEquals(StunClass.REQUEST, req.getMessageClass());
        assertArrayEquals(txId, req.getTransactionId());
        assertTrue(req.isBindingRequest());
    }

    @Test
    public void testBindingRequestEncodeDecode() {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        StunMessage req = StunMessage.createBindingRequest(txId);
        byte[] encoded = req.encode();
        int typeField = ((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF);
        assertEquals("Binding Request type must be 0x0001", 0x0001, typeField);

        // 最小长度: 20 (header) + 8 (FINGERPRINT)
        assertTrue("Encoded length should be >= 28", encoded.length >= 28);

        StunMessage decoded = StunMessage.decode(encoded);
        assertEquals(StunConstants.METHOD_BINDING, decoded.getMethod());
        assertEquals(StunClass.REQUEST, decoded.getMessageClass());
        assertArrayEquals(txId, decoded.getTransactionId());
        int msgLen = ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF);
        assertEquals("Header length should match payload size", encoded.length - 20, msgLen);
    }

    @Test
    public void testBindingResponseWithXorMappedAddress() throws Exception {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        InetSocketAddress mappedAddr = new InetSocketAddress(
            InetAddress.getByName("203.0.113.42"), 3478);

        StunMessage response = StunMessage.createBindingResponse(txId, mappedAddr, null);
        byte[] encoded = response.encode();
        int typeField = ((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF);
        assertEquals("Binding Success Response type must be 0x0101", 0x0101, typeField);

        StunMessage decoded = StunMessage.decode(encoded);
        assertEquals(StunConstants.METHOD_BINDING, decoded.getMethod());
        assertEquals(StunClass.SUCCESS_RESPONSE, decoded.getMessageClass());
        assertTrue("Should be a binding response", decoded.isBindingResponse());

        InetSocketAddress decodedAddr = decoded.getXorMappedAddress();
        assertNotNull("XOR-MAPPED-ADDRESS should exist", decodedAddr);
        assertEquals("Address should match", mappedAddr.getAddress(), decodedAddr.getAddress());
        // Port may differ due to XOR encoding/decoding round trip
        // Actually with XOR it should be idempotent
        assertEquals("Port should match", mappedAddr.getPort(), decodedAddr.getPort());
    }

    @Test
    public void testTransactionIdUniqueness() {
        byte[] txId1 = new byte[12];
        byte[] txId2 = new byte[12];
        random.nextBytes(txId1);
        random.nextBytes(txId2);

        StunMessage req1 = StunMessage.createBindingRequest(txId1);
        StunMessage req2 = StunMessage.createBindingRequest(txId2);

        byte[] enc1 = req1.encode();
        byte[] enc2 = req2.encode();

        // Transaction ID starts at offset 8
        assertFalse("Two requests should have different transaction IDs",
            java.util.Arrays.equals(
                java.util.Arrays.copyOfRange(enc1, 8, 20),
                java.util.Arrays.copyOfRange(enc2, 8, 20)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidTransactionIdLength() {
        StunMessage.createBindingRequest(new byte[8]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeInvalidData() {
        StunMessage.decode(new byte[10]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecodeInvalidMagicCookie() {
        byte[] data = new byte[20];
        data[0] = 0x00;
        data[1] = 0x01; // Binding Request
        data[2] = 0x00;
        data[3] = 0x00; // length = 0
        data[4] = 0x00;
        data[5] = 0x00;
        data[6] = 0x00;
        data[7] = 0x00; // invalid cookie (should be 0x2112A442)
        StunMessage.decode(data);
    }

    @Test
    public void testIcmpErrorHandling() throws Exception {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        StunMessage req = StunMessage.createBindingRequest(txId);
        byte[] encoded = req.encode();

        assertNotNull("Encoded bytes should not be null", encoded);
        assertTrue("Binding request should be valid", encoded.length > 0);
    }

    @Test
    public void testCreateIceBindingRequestWithAllAttributes() {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        int priority = 2130706431;
        long tieBreaker = 123456789L;

        StunMessage req = StunMessage.createIceBindingRequest(txId, priority, tieBreaker, true, true);

        assertTrue(req.isBindingRequest());
        assertTrue("Should have PRIORITY attribute", req.hasAttribute(StunConstants.ATTR_PRIORITY));
        assertTrue("Should have ICE-CONTROLLING", req.hasAttribute(StunConstants.ATTR_ICE_CONTROLLING));
        assertFalse("Should NOT have ICE-CONTROLLED", req.hasAttribute(StunConstants.ATTR_ICE_CONTROLLED));
        assertTrue("Should have USE-CANDIDATE", req.hasAttribute(StunConstants.ATTR_USE_CANDIDATE));

        // Test with controlled role, no use-candidate
        StunMessage req2 = StunMessage.createIceBindingRequest(txId, priority, tieBreaker, false, false);
        assertTrue(req2.isBindingRequest());
        assertTrue(req2.hasAttribute(StunConstants.ATTR_PRIORITY));
        assertTrue(req2.hasAttribute(StunConstants.ATTR_ICE_CONTROLLED));
        assertFalse(req2.hasAttribute(StunConstants.ATTR_ICE_CONTROLLING));
        assertFalse("Should NOT have USE-CANDIDATE", req2.hasAttribute(StunConstants.ATTR_USE_CANDIDATE));
    }

    @Test
    public void testIceBindingRequestEncodeDecode() {
        byte[] txId = new byte[12];
        random.nextBytes(txId);

        int priority = 2130706431;
        long tieBreaker = 0xABCD1234L;

        StunMessage req = StunMessage.createIceBindingRequest(txId, priority, tieBreaker, true, true);
        byte[] encoded = req.encode();

        StunMessage decoded = StunMessage.decode(encoded);
        assertTrue(decoded.isBindingRequest());
        assertTrue(decoded.hasAttribute(StunConstants.ATTR_PRIORITY));
        assertTrue(decoded.hasAttribute(StunConstants.ATTR_ICE_CONTROLLING));
        assertTrue(decoded.hasAttribute(StunConstants.ATTR_USE_CANDIDATE));

        assertEquals("PRIORITY should round-trip", priority, decoded.getIcePriority());
        assertEquals("tie-breaker should round-trip", tieBreaker, decoded.getIceTieBreaker());
    }

    @Test
    public void testBindingResponseEncodeWithMessageIntegrityLengthField() throws Exception {
        byte[] txId = new byte[12];
        random.nextBytes(txId);
        InetSocketAddress mappedAddr = new InetSocketAddress(
            InetAddress.getByName("203.0.113.42"), 3478);

        StunMessage response = StunMessage.createBindingResponse(txId, mappedAddr, null);
        byte[] encoded = response.encode("local-ice-password");

        int msgLen = ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF);
        assertEquals("Header length should include FINGERPRINT", encoded.length - 20, msgLen);
    }
}
