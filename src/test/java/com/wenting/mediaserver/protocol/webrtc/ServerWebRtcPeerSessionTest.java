package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerWebRtcPeerSessionTest {

    @Test
    public void shouldWrapPeerConnectionWithServiceSessionIdentity() throws Exception {
        StubDatagramIo transport = new StubDatagramIo();
            RTCPeerConnection peerConnection = new RTCPeerConnection(transport);
            try {
                StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
                ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                        "sess-1",
                        streamKey,
                        peerConnection,
                        new SessionDatagramIo(
                                new InetSocketAddress("127.0.0.1", 18081),
                                new DatagramIoSender() {
                                    @Override
                                    public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                }
                        )
                );

                assertEquals("sess-1", session.sessionId());
                assertSame(streamKey, session.streamKey());
                assertSame(peerConnection, session.peerConnection());
                assertNotNull(session.subscriberAdapter());
                assertEquals(1, transport.startCount);
        } finally {
            peerConnection.close();
        }
    }

    @Test
    public void shouldAttachAndRemoveSubscriberFromPublishedStream() throws Exception {
        StubDatagramIo transport = new StubDatagramIo();
        RTCPeerConnection peerConnection = new RTCPeerConnection(transport);
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);

        ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                "sess-2",
                streamKey,
                peerConnection,
                new SessionDatagramIo(
                        new InetSocketAddress("127.0.0.1", 18081),
                        new DatagramIoSender() {
                            @Override
                            public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
                                return CompletableFuture.completedFuture(null);
                            }
                        }
                )
        );

        try {
            session.attachPublishedStream(publishedStream);

            assertEquals(1, publishedStream.subscriberCount());
            MediaSubscriberAdapter adapter = session.subscriberAdapter();
            assertEquals("sess-2", adapter.sessionId());

            session.close();

            assertEquals(0, publishedStream.subscriberCount());
        } finally {
            session.close();
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
