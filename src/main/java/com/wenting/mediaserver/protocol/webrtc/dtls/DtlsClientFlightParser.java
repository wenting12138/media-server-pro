package com.wenting.mediaserver.protocol.webrtc.dtls;

public final class DtlsClientFlightParser {

    private static final int CONTENT_TYPE_CHANGE_CIPHER_SPEC = 20;
    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE = 16;
    private static final int HANDSHAKE_TYPE_FINISHED = 20;
    private static final int RECORD_HEADER_LENGTH = 13;

    public DtlsClientFlight parse(byte[] packet) {
        if (packet == null || packet.length < RECORD_HEADER_LENGTH) {
            return null;
        }
        boolean hasClientKeyExchange = false;
        boolean hasChangeCipherSpec = false;
        boolean hasFinished = false;
        int offset = 0;
        while (offset + RECORD_HEADER_LENGTH <= packet.length) {
            int contentType = packet[offset] & 0xFF;
            int recordLength = ((packet[offset + 11] & 0xFF) << 8) | (packet[offset + 12] & 0xFF);
            if (recordLength < 0 || offset + RECORD_HEADER_LENGTH + recordLength > packet.length) {
                return null;
            }
            if (contentType == CONTENT_TYPE_CHANGE_CIPHER_SPEC) {
                hasChangeCipherSpec = true;
            } else if (contentType == CONTENT_TYPE_HANDSHAKE && recordLength >= 12) {
                int handshakeType = packet[offset + RECORD_HEADER_LENGTH] & 0xFF;
                if (handshakeType == HANDSHAKE_TYPE_CLIENT_KEY_EXCHANGE) {
                    hasClientKeyExchange = true;
                } else if (handshakeType == HANDSHAKE_TYPE_FINISHED) {
                    hasFinished = true;
                }
            }
            offset += RECORD_HEADER_LENGTH + recordLength;
        }
        if (!hasClientKeyExchange && !hasChangeCipherSpec && !hasFinished) {
            return null;
        }
        return new DtlsClientFlight(hasClientKeyExchange, hasChangeCipherSpec, hasFinished, packet);
    }
}
