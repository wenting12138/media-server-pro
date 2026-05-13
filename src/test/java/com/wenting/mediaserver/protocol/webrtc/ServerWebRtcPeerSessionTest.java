package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    public void shouldPacketizeH264FrameEncryptAndSendSrtpDatagrams() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                "sess-3",
                streamKey,
                peerConnection,
                datagramIo
        );
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50000);

        try {
            session.receive(new byte[0], remoteAddress);
            session.writeInboundFrame(h264ConfigFrame(streamKey));
            session.writeInboundFrame(h264Frame(streamKey, false, new byte[]{0x41, 0x01, 0x02, 0x03}, 20L));

            assertEquals(0, sender.sentPackets.size());

            session.writeInboundFrame(h264Frame(streamKey, true, new byte[]{0x65, 0x11, 0x22, 0x33}, 40L));

            assertEquals(3, sender.sentPackets.size());
            for (SentPacket packet : sender.sentPackets) {
                assertEquals(remoteAddress, packet.target);
            }

            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket spsPacket = decrypt.unprotect(sender.sentPackets.get(0).data);
            RtpPacket ppsPacket = decrypt.unprotect(sender.sentPackets.get(1).data);
            RtpPacket keyPacket = decrypt.unprotect(sender.sentPackets.get(2).data);

            assertEquals(96, keyPacket.getPayloadType());
            assertEquals(transceiver.getSender().getSsrc(), keyPacket.getSsrc());
            assertTrue(keyPacket.getMarker());
            assertArrayEquals(new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}, spsPacket.getPayload());
            assertArrayEquals(new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}, ppsPacket.getPayload());
            assertArrayEquals(new byte[]{0x65, 0x11, 0x22, 0x33}, keyPacket.getPayload());
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

    private static InboundMediaFrame h264ConfigFrame(StreamKey streamKey) {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                streamKey,
                "video-h264",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{
                        0x01, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE1,
                        0x00, 0x08, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78,
                        0x01, 0x00, 0x04, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
                }
        );
    }

    private static InboundMediaFrame h264Frame(StreamKey streamKey, boolean keyFrame, byte[] nal, long timestampMillis) {
        byte[] payload = new byte[4 + nal.length];
        payload[0] = (byte) ((nal.length >> 24) & 0xFF);
        payload[1] = (byte) ((nal.length >> 16) & 0xFF);
        payload[2] = (byte) ((nal.length >> 8) & 0xFF);
        payload[3] = (byte) (nal.length & 0xFF);
        System.arraycopy(nal, 0, payload, 4, nal.length);
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                streamKey,
                "video-h264",
                Long.valueOf(timestampMillis),
                Long.valueOf(timestampMillis),
                keyFrame,
                false,
                null,
                payload
        );
    }

    private static byte[] keyMaterial() {
        byte[] keyMaterial = new byte[60];
        for (int i = 0; i < keyMaterial.length; i++) {
            keyMaterial[i] = (byte) (i + 1);
        }
        return keyMaterial;
    }

    private static final class RecordingDatagramIoSender implements DatagramIoSender {
        private final List<SentPacket> sentPackets = new ArrayList<SentPacket>();

        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            sentPackets.add(new SentPacket(data, target));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class SentPacket {
        private final byte[] data;
        private final InetSocketAddress target;

        private SentPacket(byte[] data, InetSocketAddress target) {
            this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
            this.target = target;
        }
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
