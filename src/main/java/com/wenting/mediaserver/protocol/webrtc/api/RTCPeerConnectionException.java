package com.wenting.mediaserver.protocol.webrtc.api;

/**
 * Exception for RTCPeerConnection errors.
 */
public class RTCPeerConnectionException extends Exception {
    public RTCPeerConnectionException(String message) {
        super(message);
    }

    public RTCPeerConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
