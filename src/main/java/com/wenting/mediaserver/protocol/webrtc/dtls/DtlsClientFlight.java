package com.wenting.mediaserver.protocol.webrtc.dtls;

public final class DtlsClientFlight {

    private final boolean hasClientKeyExchange;
    private final boolean hasChangeCipherSpec;
    private final boolean hasFinished;
    private final byte[] packet;

    public DtlsClientFlight(boolean hasClientKeyExchange, boolean hasChangeCipherSpec, boolean hasFinished, byte[] packet) {
        this.hasClientKeyExchange = hasClientKeyExchange;
        this.hasChangeCipherSpec = hasChangeCipherSpec;
        this.hasFinished = hasFinished;
        this.packet = packet == null ? new byte[0] : packet.clone();
    }

    public boolean hasClientKeyExchange() {
        return hasClientKeyExchange;
    }

    public boolean hasChangeCipherSpec() {
        return hasChangeCipherSpec;
    }

    public boolean hasFinished() {
        return hasFinished;
    }

    public byte[] packet() {
        return packet.clone();
    }

    public boolean isCompleteClientFlight() {
        return hasClientKeyExchange && hasChangeCipherSpec && hasFinished;
    }
}
