package com.wenting.mediaserver.protocol.webrtc.ice;

public final class IceCandidate {

    private final String foundation;
    private final int component;
    private final String transport;
    private final long priority;
    private final String address;
    private final int port;
    private final IceCandidateType type;

    public IceCandidate(String foundation, int component, String transport, long priority, String address, int port, IceCandidateType type) {
        this.foundation = foundation == null || foundation.trim().isEmpty() ? "1" : foundation.trim();
        this.component = component <= 0 ? 1 : component;
        this.transport = transport == null || transport.trim().isEmpty() ? "udp" : transport.trim().toLowerCase(java.util.Locale.ROOT);
        this.priority = priority;
        this.address = address == null || address.trim().isEmpty() ? "127.0.0.1" : address.trim();
        this.port = port;
        this.type = type == null ? IceCandidateType.HOST : type;
    }

    public String foundation() {
        return foundation;
    }

    public int component() {
        return component;
    }

    public String transport() {
        return transport;
    }

    public long priority() {
        return priority;
    }

    public String address() {
        return address;
    }

    public int port() {
        return port;
    }

    public IceCandidateType type() {
        return type;
    }
}
