package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RTCPeerConnectionIdleTimeoutTest {

    @Test
    void shouldFailConnectedPeerWhenInboundActivityTimesOut() throws Exception {
        RTCPeerConnection peerConnection = new RTCPeerConnection(new StubDatagramIo());
        try {
            setField(peerConnection, "connectionState", RTCPeerConnection.ConnectionState.CONNECTED);
            setField(peerConnection, "iceConnectionState", RTCPeerConnection.IceConnectionState.COMPLETED);
            setField(peerConnection, "lastInboundActivityAtMs", Long.valueOf(System.currentTimeMillis() - 40000L));

            peerConnection.checkConnectionHealthNow();

            assertEquals(RTCPeerConnection.ConnectionState.FAILED, peerConnection.getConnectionState());
            assertEquals(RTCPeerConnection.IceConnectionState.FAILED, peerConnection.getIceConnectionState());
        } finally {
            peerConnection.close();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = RTCPeerConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class StubDatagramIo implements DatagramIo {
        private UdpTransport.PacketHandler packetHandler;

        @Override
        public void setPacketHandler(UdpTransport.PacketHandler handler) {
            this.packetHandler = handler;
        }

        @Override
        public void start() {
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
        }

        @Override
        public void close() {
        }
    }
}
