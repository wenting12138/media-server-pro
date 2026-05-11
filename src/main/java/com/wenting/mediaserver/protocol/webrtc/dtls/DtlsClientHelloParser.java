package com.wenting.mediaserver.protocol.webrtc.dtls;

import java.util.Arrays;

public final class DtlsClientHelloParser {

    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int HANDSHAKE_TYPE_CLIENT_HELLO = 1;
    private static final int DTLS_RECORD_HEADER_LENGTH = 13;
    private static final int DTLS_HANDSHAKE_HEADER_LENGTH = 12;

    public DtlsClientHello parse(byte[] packet) {
        if (packet == null || packet.length < DTLS_RECORD_HEADER_LENGTH + DTLS_HANDSHAKE_HEADER_LENGTH + 34) {
            return null;
        }
        if (unsignedByte(packet[0]) != CONTENT_TYPE_HANDSHAKE) {
            return null;
        }
        int version = unsignedShort(packet, 1);
        int recordLength = unsignedShort(packet, 11);
        if (recordLength <= 0 || DTLS_RECORD_HEADER_LENGTH + recordLength > packet.length) {
            return null;
        }
        if (unsignedByte(packet[13]) != HANDSHAKE_TYPE_CLIENT_HELLO) {
            return null;
        }
        int fragmentOffset = unsignedMedium(packet, 19);
        int fragmentLength = unsignedMedium(packet, 22);
        if (fragmentOffset != 0 || fragmentLength <= 0) {
            return null;
        }
        int bodyOffset = DTLS_RECORD_HEADER_LENGTH + DTLS_HANDSHAKE_HEADER_LENGTH;
        if (bodyOffset + 34 > packet.length) {
            return null;
        }
        byte[] recordSequence = Arrays.copyOfRange(packet, 5, 11);
        byte[] random = Arrays.copyOfRange(packet, bodyOffset + 2, bodyOffset + 34);
        int sessionIdLengthOffset = bodyOffset + 34;
        if (sessionIdLengthOffset >= packet.length) {
            return null;
        }
        int sessionIdLength = unsignedByte(packet[sessionIdLengthOffset]);
        int cookieLengthOffset = sessionIdLengthOffset + 1 + sessionIdLength;
        if (cookieLengthOffset >= packet.length) {
            return null;
        }
        int cookieLength = unsignedByte(packet[cookieLengthOffset]);
        int cipherSuitesLengthOffset = cookieLengthOffset + 1 + cookieLength;
        if (cipherSuitesLengthOffset + 2 > packet.length) {
            return null;
        }
        int cipherSuitesLength = unsignedShort(packet, cipherSuitesLengthOffset);
        return new DtlsClientHello(version, recordSequence, random, cipherSuitesLength / 2);
    }

    public boolean looksLikeClientHello(byte[] packet) {
        return parse(packet) != null;
    }

    private static int unsignedByte(byte value) {
        return value & 0xFF;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int unsignedMedium(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 16)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | (bytes[offset + 2] & 0xFF);
    }
}
