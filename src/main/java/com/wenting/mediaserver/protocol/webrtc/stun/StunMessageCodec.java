package com.wenting.mediaserver.protocol.webrtc.stun;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class StunMessageCodec {

    public static final int MAGIC_COOKIE = 0x2112A442;

    public StunMessage decode(byte[] bytes) {
        if (bytes == null || bytes.length < 20) {
            return null;
        }
        int typeCode = readUnsignedShort(bytes, 0);
        StunMessageType type = StunMessageType.fromCode(typeCode);
        if (type == null) {
            return null;
        }
        int length = readUnsignedShort(bytes, 2);
        if (bytes.length < 20 + length) {
            return null;
        }
        int magicCookie = readInt(bytes, 4);
        if (magicCookie != MAGIC_COOKIE) {
            return null;
        }
        byte[] transactionId = copy(bytes, 8, 12);
        String username = null;
        Long priority = null;
        boolean useCandidate = false;
        Long iceControlled = null;
        Long iceControlling = null;
        InetSocketAddress xorMappedAddress = null;
        int offset = 20;
        while (offset + 4 <= 20 + length) {
            int attributeCode = readUnsignedShort(bytes, offset);
            int attributeLength = readUnsignedShort(bytes, offset + 2);
            offset += 4;
            if (offset + attributeLength > bytes.length) {
                return null;
            }
            StunAttributeType attributeType = StunAttributeType.fromCode(attributeCode);
            if (attributeType == StunAttributeType.USERNAME) {
                username = new String(bytes, offset, attributeLength, java.nio.charset.StandardCharsets.UTF_8);
            } else if (attributeType == StunAttributeType.PRIORITY && attributeLength >= 4) {
                priority = Long.valueOf(readInt(bytes, offset) & 0xFFFFFFFFL);
            } else if (attributeType == StunAttributeType.USE_CANDIDATE) {
                useCandidate = true;
            } else if (attributeType == StunAttributeType.ICE_CONTROLLED && attributeLength >= 8) {
                iceControlled = Long.valueOf(readLong(bytes, offset));
            } else if (attributeType == StunAttributeType.ICE_CONTROLLING && attributeLength >= 8) {
                iceControlling = Long.valueOf(readLong(bytes, offset));
            } else if (attributeType == StunAttributeType.XOR_MAPPED_ADDRESS) {
                xorMappedAddress = decodeXorMappedAddress(bytes, offset, attributeLength);
            }
            offset += paddedLength(attributeLength);
        }
        return new StunMessage(type, transactionId, username, priority, useCandidate, iceControlled, iceControlling, xorMappedAddress);
    }

    public byte[] encodeBindingSuccessResponse(byte[] transactionId, InetSocketAddress mappedAddress) {
        byte[] xorMapped = encodeXorMappedAddress(mappedAddress);
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        writeAttribute(attributes, StunAttributeType.XOR_MAPPED_ADDRESS.code(), xorMapped);
        byte[] attributeBytes = attributes.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsignedShort(out, StunMessageType.BINDING_SUCCESS_RESPONSE.code());
        writeUnsignedShort(out, attributeBytes.length);
        writeInt(out, MAGIC_COOKIE);
        out.write(transactionId, 0, transactionId.length);
        out.write(attributeBytes, 0, attributeBytes.length);
        return out.toByteArray();
    }

    private static InetSocketAddress decodeXorMappedAddress(byte[] bytes, int offset, int length) {
        if (length < 8) {
            return null;
        }
        int family = bytes[offset + 1] & 0xFF;
        if (family != 0x01) {
            return null;
        }
        int port = readUnsignedShort(bytes, offset + 2) ^ (MAGIC_COOKIE >>> 16);
        byte[] addressBytes = new byte[4];
        addressBytes[0] = (byte) (bytes[offset + 4] ^ ((MAGIC_COOKIE >> 24) & 0xFF));
        addressBytes[1] = (byte) (bytes[offset + 5] ^ ((MAGIC_COOKIE >> 16) & 0xFF));
        addressBytes[2] = (byte) (bytes[offset + 6] ^ ((MAGIC_COOKIE >> 8) & 0xFF));
        addressBytes[3] = (byte) (bytes[offset + 7] ^ (MAGIC_COOKIE & 0xFF));
        try {
            return new InetSocketAddress(InetAddress.getByAddress(addressBytes), port);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encodeXorMappedAddress(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return new byte[0];
        }
        byte[] raw = address.getAddress().getAddress();
        if (raw.length != 4) {
            return new byte[0];
        }
        byte[] value = new byte[8];
        value[0] = 0x00;
        value[1] = 0x01;
        int port = address.getPort() ^ (MAGIC_COOKIE >>> 16);
        value[2] = (byte) ((port >> 8) & 0xFF);
        value[3] = (byte) (port & 0xFF);
        value[4] = (byte) (raw[0] ^ ((MAGIC_COOKIE >> 24) & 0xFF));
        value[5] = (byte) (raw[1] ^ ((MAGIC_COOKIE >> 16) & 0xFF));
        value[6] = (byte) (raw[2] ^ ((MAGIC_COOKIE >> 8) & 0xFF));
        value[7] = (byte) (raw[3] ^ (MAGIC_COOKIE & 0xFF));
        return value;
    }

    private static void writeAttribute(ByteArrayOutputStream out, int type, byte[] value) {
        byte[] safeValue = value == null ? new byte[0] : value;
        writeUnsignedShort(out, type);
        writeUnsignedShort(out, safeValue.length);
        out.write(safeValue, 0, safeValue.length);
        int padding = paddedLength(safeValue.length) - safeValue.length;
        for (int i = 0; i < padding; i++) {
            out.write(0x00);
        }
    }

    private static int paddedLength(int length) {
        return (length + 3) & ~0x03;
    }

    private static byte[] copy(byte[] bytes, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        return copy;
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static long readLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 56)
                | ((long) (bytes[offset + 1] & 0xFF) << 48)
                | ((long) (bytes[offset + 2] & 0xFF) << 40)
                | ((long) (bytes[offset + 3] & 0xFF) << 32)
                | ((long) (bytes[offset + 4] & 0xFF) << 24)
                | ((long) (bytes[offset + 5] & 0xFF) << 16)
                | ((long) (bytes[offset + 6] & 0xFF) << 8)
                | ((long) (bytes[offset + 7] & 0xFF));
    }

    private static void writeUnsignedShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
