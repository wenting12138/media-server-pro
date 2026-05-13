package com.wenting.mediaserver.protocol.webrtc.core.srtp;

/**
 * SRTP operation exception.
 */
public class SrtpException extends Exception {
    public SrtpException(String message) {
        super(message);
    }
    public SrtpException(String message, Throwable cause) {
        super(message, cause);
    }
}
