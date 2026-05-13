package com.wenting.mediaserver.protocol.webrtc.core.stun;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * STUN message (RFC 5389).
 *
 * 完整实现 STUN 消息的编码与解码，包括：
 * - 消息头 (20 bytes)
 * - 各种属性 (TLV format)
 * - XOR-MAPPED-ADDRESS 的编解码
 * - FINGERPRINT 属性
 *
 * 用法:
 *   StunMessage req = StunMessage.createBindingRequest(transactionId);
 *   byte[] wire = req.encode();
 *
 *   StunMessage resp = StunMessage.decode(wire);
 *   InetSocketAddress addr = resp.getXorMappedAddress();
 */
public class StunMessage {

    private final int method;
    private final int messageClass;
    private final byte[] transactionId;
    private final List<Attribute> attributes;

    public StunMessage(int method, int messageClass, byte[] transactionId,
                       List<Attribute> attributes) {
        if (transactionId == null || transactionId.length != 12) {
            throw new IllegalArgumentException("Transaction ID must be exactly 12 bytes");
        }
        this.method = method & 0x3FFF;
        this.messageClass = messageClass & 0x3;
        this.transactionId = transactionId.clone();
        this.attributes = attributes != null
            ? Collections.unmodifiableList(new ArrayList<>(attributes))
            : Collections.emptyList();
    }

    // ---- 工厂方法 ----

    public static StunMessage createBindingRequest(byte[] transactionId) {
        return new StunMessage(
            StunConstants.METHOD_BINDING,
            StunClass.REQUEST,
            transactionId,
            Collections.<Attribute>emptyList()
        );
    }

    /**
     * Create a Binding Request with ICE attributes (RFC 5245 Section 7.1.2).
     * Includes PRIORITY, ICE-CONTROLLING/CONTROLLED, and optionally USE-CANDIDATE.
     */
    public static StunMessage createIceBindingRequest(
            byte[] transactionId, int priority, long tieBreaker,
            boolean controlling, boolean useCandidate) {
        List<Attribute> attrs = new ArrayList<>();

        // PRIORITY attribute (4 bytes)
        byte[] prioBytes = ByteBuffer.allocate(4).putInt(priority).array();
        attrs.add(new Attribute(StunConstants.ATTR_PRIORITY, prioBytes));

        // ICE-CONTROLLING or ICE-CONTROLLED (8 bytes tiebreaker)
        byte[] tbBytes = ByteBuffer.allocate(8).putLong(tieBreaker).array();
        attrs.add(new Attribute(
            controlling ? StunConstants.ATTR_ICE_CONTROLLING : StunConstants.ATTR_ICE_CONTROLLED,
            tbBytes));

        // USE-CANDIDATE (empty value)
        if (useCandidate) {
            attrs.add(new Attribute(StunConstants.ATTR_USE_CANDIDATE, new byte[0]));
        }

        return new StunMessage(StunConstants.METHOD_BINDING, StunClass.REQUEST,
            transactionId, attrs);
    }

    public static StunMessage createBindingResponse(byte[] transactionId,
                                                     InetSocketAddress mappedAddress,
                                                     byte[] xorAddress) {
        List<Attribute> attrs = new ArrayList<>();
        attrs.add(new XorMappedAddressAttribute(mappedAddress));
        if (xorAddress != null) {
            String sw = "webrtc-java/0.1.0";
            byte[] swBytes = sw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            attrs.add(new Attribute(StunConstants.ATTR_SOFTWARE, swBytes));
        }
        return new StunMessage(
            StunConstants.METHOD_BINDING,
            StunClass.SUCCESS_RESPONSE,
            transactionId,
            attrs
        );
    }

    // ---- 编解码 ----

    public byte[] encode() {
        byte[][] attrData = new byte[attributes.size()][];
        int totalAttrLength = 0;
        for (int i = 0; i < attributes.size(); i++) {
            attrData[i] = attributes.get(i).encode();
            totalAttrLength += attrData[i].length;
        }

        int messageLength = totalAttrLength + 8; // +8 for FINGERPRINT

        ByteBuffer buf = ByteBuffer.allocate(StunConstants.HEADER_SIZE + messageLength);

        int typeField = encodeTypeField();
        buf.putShort((short) typeField);
        buf.putShort((short) totalAttrLength);
        buf.putInt(StunConstants.MAGIC_COOKIE);
        buf.put(transactionId);

        for (byte[] attr : attrData) {
            buf.put(attr);
        }

        int crc = calculateCrc32(buf.array(), buf.position());
        int fingerprintValue = crc ^ 0x5354554E;
        buf.putShort((short) StunConstants.ATTR_FINGERPRINT);
        buf.putShort((short) 4);
        buf.putInt(fingerprintValue);

        return buf.array();
    }

    public static StunMessage decode(byte[] data) {
        if (data == null || data.length < StunConstants.HEADER_SIZE) {
            throw new IllegalArgumentException("STUN message too short: " +
                (data != null ? data.length : "null"));
        }

        ByteBuffer buf = ByteBuffer.wrap(data);

        int typeField = buf.getShort() & 0xFFFF;
        int messageLength = buf.getShort() & 0xFFFF;
        int cookie = buf.getInt();

        if (cookie != StunConstants.MAGIC_COOKIE) {
            throw new IllegalArgumentException("Invalid STUN magic cookie: 0x" +
                Integer.toHexString(cookie));
        }

        byte[] transactionId = new byte[12];
        buf.get(transactionId);

        // RFC 5389 type field:
        //   M0-M3: bits 0-3, C0: bit 4, M4: bit 5, M5: bit 6, C1: bit 7, M6-M11: bits 8-13
        int method = (typeField & 0x000F);
        method |= ((typeField & 0x0060) >> 1);   // M4-M5 from bits 5-6
        method |= ((typeField & 0x3F00) >> 2);   // M6-M11 from bits 8-13

        int classBits = ((typeField & 0x0010) >> 4)
                      | ((typeField & 0x0080) >> 6);

        List<Attribute> attrs = new ArrayList<>();
        int offset = StunConstants.HEADER_SIZE;
        int remaining = Math.min(messageLength, data.length - StunConstants.HEADER_SIZE);

        while (remaining >= 4) {
            int attrType = (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
            int attrLen = (data[offset + 2] & 0xFF) << 8 | (data[offset + 3] & 0xFF);
            int paddedLen = (attrLen + 3) & ~3;

            if (attrLen > remaining - 4) break;

            byte[] attrValue = new byte[attrLen];
            System.arraycopy(data, offset + 4, attrValue, 0, attrLen);

            if (attrType == StunConstants.ATTR_XOR_MAPPED_ADDRESS) {
                attrs.add(decodeXorMappedAddress(attrValue, transactionId));
            } else if (attrType == StunConstants.ATTR_MAPPED_ADDRESS) {
                attrs.add(decodeMappedAddress(attrValue));
            } else {
                attrs.add(new Attribute(attrType, attrValue));
            }

            offset += 4 + paddedLen;
            remaining -= 4 + paddedLen;
        }

        return new StunMessage(method, classBits, transactionId, attrs);
    }

    // ---- 访问器 ----

    public int getMethod() { return method; }
    public int getMessageClass() { return messageClass; }
    public byte[] getTransactionId() { return transactionId.clone(); }
    public List<Attribute> getAttributes() { return attributes; }

    public boolean isBindingRequest() {
        return method == StunConstants.METHOD_BINDING
            && messageClass == StunClass.REQUEST;
    }

    public boolean isBindingResponse() {
        return method == StunConstants.METHOD_BINDING
            && messageClass == StunClass.SUCCESS_RESPONSE;
    }

    // ---- ICE attribute helpers (RFC 5245) ----

    public boolean hasAttribute(int attrType) {
        for (Attribute attr : attributes) {
            if (attr.type == attrType) return true;
        }
        return false;
    }

    public byte[] getAttributeValue(int attrType) {
        for (Attribute attr : attributes) {
            if (attr.type == attrType) return attr.value;
        }
        return null;
    }

    public int getIcePriority() {
        byte[] val = getAttributeValue(StunConstants.ATTR_PRIORITY);
        if (val == null || val.length < 4) return 0;
        return ByteBuffer.wrap(val).getInt();
    }

    public long getIceTieBreaker() {
        byte[] val = getAttributeValue(StunConstants.ATTR_ICE_CONTROLLING);
        if (val == null || val.length < 8) {
            val = getAttributeValue(StunConstants.ATTR_ICE_CONTROLLED);
        }
        if (val == null || val.length < 8) return 0;
        return ByteBuffer.wrap(val).getLong();
    }

    public InetSocketAddress getXorMappedAddress() {
        for (Attribute attr : attributes) {
            if (attr instanceof XorMappedAddressAttribute) {
                return ((XorMappedAddressAttribute) attr).getAddress();
            }
            if (attr.type == StunConstants.ATTR_MAPPED_ADDRESS
                && attr instanceof MappedAddressAttribute) {
                return ((MappedAddressAttribute) attr).getAddress();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "StunMessage{method=" + method + ", class=" + messageClass
            + ", transactionId=" + bytesToHex(transactionId)
            + ", attributes=" + attributes.size() + "}";
    }

    // ---- 内部方法 ----

    private int encodeTypeField() {
        int type = 0;
        type |= (method & 0x000F);               // M0-M3 -> bits 0-3
        type |= ((method & 0x0030) << 1);        // M4-M5 -> bits 5-6
        type |= ((method & 0x0FC0) << 2);        // M6-M11 -> bits 8-13
        type |= ((messageClass & 0x01) << 4);    // C0 -> bit 4
        type |= ((messageClass & 0x02) << 6);    // C1 -> bit 7
        return type;
    }

    private static XorMappedAddressAttribute decodeXorMappedAddress(
            byte[] value, byte[] transactionId) {
        int family = value[1] & 0xFF;
        int port = (value[2] & 0xFF) << 8 | (value[3] & 0xFF);
        port ^= (StunConstants.MAGIC_COOKIE >> 16);

        byte[] address;
        int addrLen;
        if (family == StunConstants.ADDR_IPV4) {
            addrLen = 4;
            address = new byte[4];
            System.arraycopy(value, 4, address, 0, 4);
            byte[] cookieBytes = ByteBuffer.allocate(4).putInt(
                StunConstants.MAGIC_COOKIE).array();
            for (int i = 0; i < 4; i++) {
                address[i] ^= cookieBytes[i];
            }
        } else if (family == StunConstants.ADDR_IPV6) {
            addrLen = 16;
            address = new byte[16];
            System.arraycopy(value, 4, address, 0, 16);
            byte[] cookieBytes = ByteBuffer.allocate(4).putInt(
                StunConstants.MAGIC_COOKIE).array();
            for (int i = 0; i < 4; i++) {
                address[i] ^= cookieBytes[i];
            }
            for (int i = 4; i < 16; i++) {
                address[i] ^= transactionId[i - 4];
            }
        } else {
            throw new IllegalArgumentException("Unknown address family: " + family);
        }

        try {
            InetAddress inet = InetAddress.getByAddress(address);
            return new XorMappedAddressAttribute(new InetSocketAddress(inet, port));
        } catch (UnknownHostException e) {
            throw new RuntimeException("Invalid address", e);
        }
    }

    private static MappedAddressAttribute decodeMappedAddress(byte[] value) {
        int family = value[1] & 0xFF;
        int port = (value[2] & 0xFF) << 8 | (value[3] & 0xFF);

        byte[] address;
        if (family == StunConstants.ADDR_IPV4) {
            address = new byte[4];
            System.arraycopy(value, 4, address, 0, 4);
        } else {
            address = new byte[16];
            System.arraycopy(value, 4, address, 0, 16);
        }

        try {
            InetAddress inet = InetAddress.getByAddress(address);
            return new MappedAddressAttribute(new InetSocketAddress(inet, port));
        } catch (UnknownHostException e) {
            throw new RuntimeException("Invalid address", e);
        }
    }

    private static int calculateCrc32(byte[] data, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xEDB88320;
                } else {
                    crc = crc >>> 1;
                }
            }
        }
        return ~crc;
    }

    static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = "0123456789ABCDEF".charAt(v >> 4);
            out[i * 2 + 1] = "0123456789ABCDEF".charAt(v & 0x0F);
        }
        return new String(out);
    }

    // ---- 属性类型 ----

    public static class Attribute {
        public final int type;
        public final byte[] value;

        public Attribute(int type, byte[] value) {
            this.type = type;
            this.value = Objects.requireNonNull(value);
        }

        public byte[] encode() {
            int paddedLen = (value.length + 3) & ~3;
            ByteBuffer buf = ByteBuffer.allocate(4 + paddedLen);
            buf.putShort((short) type);
            buf.putShort((short) value.length);
            buf.put(value);
            for (int i = value.length; i < paddedLen; i++) {
                buf.put((byte) 0);
            }
            return buf.array();
        }

        @Override
        public String toString() {
            return "Attribute{type=0x" + Integer.toHexString(type) + ", len=" + value.length + "}";
        }
    }

    public static class XorMappedAddressAttribute extends Attribute {
        private final InetSocketAddress address;

        public XorMappedAddressAttribute(InetSocketAddress address) {
            super(StunConstants.ATTR_XOR_MAPPED_ADDRESS, buildValue(address));
            this.address = address;
        }

        public InetSocketAddress getAddress() { return address; }

        private static byte[] buildValue(InetSocketAddress addr) {
            InetAddress inet = addr.getAddress();
            boolean isV6 = inet instanceof Inet6Address;
            int port = addr.getPort();
            int portXor = port ^ (StunConstants.MAGIC_COOKIE >> 16);

            int addrLen = isV6 ? 16 : 4;
            ByteBuffer buf = ByteBuffer.allocate(1 + 1 + 2 + addrLen);
            buf.put((byte) 0);
            buf.put((byte) (isV6 ? StunConstants.ADDR_IPV6 : StunConstants.ADDR_IPV4));
            buf.putShort((short) portXor);

            byte[] addrBytes = inet.getAddress();
            byte[] cookieBytes = ByteBuffer.allocate(4).putInt(
                StunConstants.MAGIC_COOKIE).array();
            for (int i = 0; i < addrLen; i++) {
                byte xored;
                if (i < 4) {
                    xored = (byte) (addrBytes[i] ^ cookieBytes[i]);
                } else {
                    xored = addrBytes[i];
                }
                buf.put(xored);
            }
            return buf.array();
        }
    }

    public static class MappedAddressAttribute extends Attribute {
        private final InetSocketAddress address;

        @SuppressWarnings("unused")
        public MappedAddressAttribute(InetSocketAddress address) {
            super(StunConstants.ATTR_MAPPED_ADDRESS, buildValue(address));
            this.address = address;
        }

        public InetSocketAddress getAddress() { return address; }

        private static byte[] buildValue(InetSocketAddress addr) {
            InetAddress inet = addr.getAddress();
            boolean isV6 = inet instanceof Inet6Address;
            int addrLen = isV6 ? 16 : 4;
            ByteBuffer buf = ByteBuffer.allocate(1 + 1 + 2 + addrLen);
            buf.put((byte) 0);
            buf.put((byte) (isV6 ? StunConstants.ADDR_IPV6 : StunConstants.ADDR_IPV4));
            buf.putShort((short) addr.getPort());
            buf.put(inet.getAddress());
            return buf.array();
        }
    }
}
