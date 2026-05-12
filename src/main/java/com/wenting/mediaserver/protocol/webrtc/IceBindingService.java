package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageCodec;
import com.wenting.mediaserver.protocol.webrtc.stun.StunMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public final class IceBindingService {

    private static final Logger log = LoggerFactory.getLogger(IceBindingService.class);

    private final StunMessageCodec codec = new StunMessageCodec();

    public byte[] handleBindingRequest(IceAgent iceAgent, byte[] packet, InetSocketAddress remoteAddress) {
        if (iceAgent == null || packet == null || remoteAddress == null) {
            log.warn("handleBindingRequest null args iceAgent={} packet={} remote={}", iceAgent != null, packet != null, remoteAddress);
            return null;
        }
        StunMessage message = codec.decode(packet);
        if (message == null || message.type() != StunMessageType.BINDING_REQUEST) {
            log.warn("handleBindingRequest decode failed or not BINDING_REQUEST");
            return null;
        }
        if (!iceAgent.acceptsRemoteUsername(message.username())) {
            log.warn("handleBindingRequest username not accepted username={} localUfrag={}",
                    message.username(), iceAgent.localUfrag());
            return null;
        }
        log.debug("handleBindingRequest accepted ufrag={} username={}", iceAgent.localUfrag(), message.username());
        return codec.encodeBindingSuccessResponse(message.transactionId(), remoteAddress, iceAgent.localPwd());
    }
}
