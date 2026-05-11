package com.wenting.mediaserver.protocol.webrtc.dtls;

public enum DtlsTransportState {
    NEW,
    CLIENT_HELLO_RECEIVED,
    SERVER_HELLO_PREPARED,
    SERVER_HELLO_SENT,
    SRTP_KEYING_EXPORTED
}
