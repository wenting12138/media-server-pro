package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ServerWebRtcPeerSessionTest {

    @Test
    public void shouldWrapPeerConnectionWithServiceSessionIdentity() throws Exception {
        StubDatagramIo transport = new StubDatagramIo();
        RTCPeerConnection peerConnection = new RTCPeerConnection(transport);
        try {
            StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
            ServerWebRtcPeerSession session = new ServerWebRtcPeerSession("sess-1", streamKey, peerConnection);

            assertEquals("sess-1", session.sessionId());
            assertSame(streamKey, session.streamKey());
            assertSame(peerConnection, session.peerConnection());
            assertEquals(1, transport.startCount);
        } finally {
            peerConnection.close();
        }
    }

    @Test
    public void shouldNotCloseInjectedTransportWhenPeerConnectionCloses() throws Exception {
        StubDatagramIo transport = new StubDatagramIo();
        RTCPeerConnection peerConnection = new RTCPeerConnection(transport);

        peerConnection.close();

        assertTrue(transport.started);
        assertEquals(0, transport.closeCount);
    }

    private static final class StubDatagramIo implements DatagramIo {
        private boolean started;
        private int startCount;
        private int closeCount;
        private UdpTransport.PacketHandler packetHandler;

        @Override
        public void setPacketHandler(UdpTransport.PacketHandler handler) {
            this.packetHandler = handler;
        }

        @Override
        public void start() {
            this.started = true;
            this.startCount++;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 18081);
        }

        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close(long timeout, TimeUnit unit) {
            this.closeCount++;
        }

        @Override
        public void close() {
            this.closeCount++;
        }
    }
}
