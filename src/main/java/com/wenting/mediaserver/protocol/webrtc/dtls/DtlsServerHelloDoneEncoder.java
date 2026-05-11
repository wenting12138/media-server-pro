package com.wenting.mediaserver.protocol.webrtc.dtls;

public final class DtlsServerHelloDoneEncoder {

    private static final int HANDSHAKE_TYPE_SERVER_HELLO_DONE = 14;

    public byte[] encode() {
        return DtlsHandshakeEncoderSupport.encodeHandshakeRecord(HANDSHAKE_TYPE_SERVER_HELLO_DONE, new byte[0]);
    }
}
