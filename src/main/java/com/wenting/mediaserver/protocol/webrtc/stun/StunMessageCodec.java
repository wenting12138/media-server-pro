package com.wenting.mediaserver.protocol.webrtc.stun;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.zip.CRC32;

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
        return encodeBindingSuccessResponse(transactionId, mappedAddress, null);
    }

    public byte[] encodeBindingSuccessResponse(byte[] transactionId, InetSocketAddress mappedAddress, String localIcePwd) {
        byte[] xorMappedValue = encodeXorMappedAddress(mappedAddress);
        byte[] xaddrAttr = encodeAttribute(StunAttributeType.XOR_MAPPED_ADDRESS.code(), xorMappedValue);

        // Build partial response with message length that includes MESSAGE-INTEGRITY
        int miAttrTotal = 4 + 20; // attribute header + HMAC-SHA1
        int partialBodyLength = xaddrAttr.length + miAttrTotal;

        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        writeUnsignedShort(partial, StunMessageType.BINDING_SUCCESS_RESPONSE.code());
        writeUnsignedShort(partial, partialBodyLength);
        writeInt(partial, MAGIC_COOKIE);
        partial.write(transactionId, 0, transactionId.length);
        partial.write(xaddrAttr, 0, xaddrAttr.length);

        byte[] partialBytes = partial.toByteArray();

        // Compute and append MESSAGE-INTEGRITY (HMAC-SHA1 with local ICE password)
        if (localIcePwd != null && !localIcePwd.isEmpty()) {
            byte[] hmac = hmacSha1(partialBytes, localIcePwd);
            byte[] miAttr = encodeAttribute(StunAttributeType.MESSAGE_INTEGRITY.code(), hmac);
            partial.write(miAttr, 0, miAttr.length);

            // Update message length to also include FINGERPRINT
            int fpAttrTotal = 4 + 4; // attribute header + CRC32
            int finalBodyLength = partialBodyLength + fpAttrTotal;

            byte[] withMi = partial.toByteArray();
            withMi[2] = (byte) ((finalBodyLength >> 8) & 0xFF);
            withMi[3] = (byte) (finalBodyLength & 0xFF);

            // Compute and append FINGERPRINT (CRC32 XOR 0x5354554E)
            long crc = crc32(withMi) ^ 0x5354554EL;
            byte[] fpValue = new byte[] {
                    (byte) ((crc >> 24) & 0xFF),
                    (byte) ((crc >> 16) & 0xFF),
                    (byte) ((crc >> 8) & 0xFF),
                    (byte) (crc & 0xFF)
            };

            ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
            finalOut.write(withMi, 0, withMi.length);
            byte[] fpAttr = encodeAttribute(StunAttributeType.FINGERPRINT.code(), fpValue);
            finalOut.write(fpAttr, 0, fpAttr.length);
            return finalOut.toByteArray();
        }

        return partialBytes;
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

    private static byte[] encodeAttribute(int type, byte[] value) {
        byte[] safeValue = value == null ? new byte[0] : value;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsignedShort(out, type);
        writeUnsignedShort(out, safeValue.length);
        out.write(safeValue, 0, safeValue.length);
        for (int i = paddedLength(safeValue.length) - safeValue.length; i > 0; i--) {
            out.write(0x00);
        }
        return out.toByteArray();
    }

    private static byte[] hmacSha1(byte[] data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute STUN MESSAGE-INTEGRITY", e);
        }
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
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
