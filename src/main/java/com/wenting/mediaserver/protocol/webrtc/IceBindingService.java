package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageCodec;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageType;

import java.net.InetSocketAddress;

public final class IceBindingService {

    private final StunMessageCodec codec = new StunMessageCodec();

    public byte[] handleBindingRequest(IceAgent iceAgent, byte[] packet, InetSocketAddress remoteAddress) {
        if (iceAgent == null || packet == null || remoteAddress == null) {
            return null;
        }
        StunMessage message = codec.decode(packet);
        if (message == null || message.type() != StunMessageType.BINDING_REQUEST) {
            return null;
        }
        if (!iceAgent.acceptsRemoteUsername(message.username())) {
            return null;
        }
        return codec.encodeBindingSuccessResponse(message.transactionId(), remoteAddress);
    }
}
