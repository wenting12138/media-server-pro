package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidatePair;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IceAgentPeerReflexiveTest {

    @Test
    void shouldCreatePeerReflexivePairFromIncomingBindingRequest() {
        RecordingDatagramIo transport = new RecordingDatagramIo(new InetSocketAddress("127.0.0.1", 18081));
        IceAgent agent = new IceAgent(transport, IceAgent.Role.CONTROLLED);
        try {
            agent.addLocalCandidates(new ArrayList<IceCandidate>() {{
                add(new IceCandidate("1", 1,
                        new InetSocketAddress("127.0.0.1", 18081), CandidateType.HOST));
            }});
            agent.startGathering();

            byte[] txId = new byte[12];
            StunMessage request = StunMessage.createIceBindingRequest(
                    txId,
                    1234,
                    5678L,
                    true,
                    true);
            InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50000);

            agent.handleStunMessage(request, remoteAddress);

            assertEquals(IceAgent.State.COMPLETED, agent.getState());
            assertEquals(1, agent.getRemoteCandidates().size());
            assertEquals(CandidateType.PEER_REFLEXIVE, agent.getRemoteCandidates().get(0).getType());
            assertEquals(remoteAddress, agent.getRemoteCandidates().get(0).getAddress());
            assertNotNull(agent.getSelectedPair());
            assertEquals(CandidatePair.State.NOMINATED, agent.getSelectedPair().getState());
            assertEquals(1, transport.sendCount);
        } finally {
            agent.shutdown();
        }
    }

    private static final class RecordingDatagramIo implements DatagramIo {
        private final InetSocketAddress localAddress;
        private int sendCount;

        private RecordingDatagramIo(InetSocketAddress localAddress) {
            this.localAddress = localAddress;
        }

        @Override
        public void setPacketHandler(UdpTransport.PacketHandler handler) {
        }

        @Override
        public void start() {
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            sendCount++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close(long timeout, TimeUnit unit) {
        }

        @Override
        public void close() {
        }
    }
}
