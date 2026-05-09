package com.wenting.mediaserver.protocol.webrtc.stun;

import java.net.InetSocketAddress;

public final class StunMessage {

    private final StunMessageType type;
    private final byte[] transactionId;
    private final String username;
    private final Long priority;
    private final boolean useCandidate;
    private final Long iceControlled;
    private final Long iceControlling;
    private final InetSocketAddress xorMappedAddress;

    public StunMessage(
            StunMessageType type,
            byte[] transactionId,
            String username,
            Long priority,
            boolean useCandidate,
            Long iceControlled,
            Long iceControlling,
            InetSocketAddress xorMappedAddress
    ) {
        this.type = type;
        this.transactionId = transactionId == null ? new byte[12] : java.util.Arrays.copyOf(transactionId, transactionId.length);
        this.username = username;
        this.priority = priority;
        this.useCandidate = useCandidate;
        this.iceControlled = iceControlled;
        this.iceControlling = iceControlling;
        this.xorMappedAddress = xorMappedAddress;
    }

    public StunMessageType type() {
        return type;
    }

    public byte[] transactionId() {
        return java.util.Arrays.copyOf(transactionId, transactionId.length);
    }

    public String username() {
        return username;
    }

    public Long priority() {
        return priority;
    }

    public boolean useCandidate() {
        return useCandidate;
    }

    public Long iceControlled() {
        return iceControlled;
    }

    public Long iceControlling() {
        return iceControlling;
    }

    public InetSocketAddress xorMappedAddress() {
        return xorMappedAddress;
    }
}
