package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RtcpFeedbackListener;
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

public class WebRtcPlaybackPeerSessionTest {

    @Test
    public void shouldWrapPeerConnectionWithServiceSessionIdentity() throws Exception {
        StubDatagramIo transport = new StubDatagramIo();
            RTCPeerConnection peerConnection = new RTCPeerConnection(transport);
            try {
                StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
                WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
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

        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
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
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
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
    public void shouldPacketizeG711FrameEncryptAndSendSrtpDatagram() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(0));
        transceiver.setNegotiatedClockRate(Integer.valueOf(8000));
        transceiver.setNegotiatedCodecType(CodecType.G711U);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
                "sess-4",
                streamKey,
                peerConnection,
                datagramIo
        );
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50002);

        try {
            session.receive(new byte[0], remoteAddress);
            byte[] payload = new byte[]{0x11, 0x22, 0x33, 0x44};
            session.writeInboundFrame(new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.AUDIO,
                    CodecType.G711U,
                    "publisher",
                    streamKey,
                    "audio-g711u",
                    Long.valueOf(20L),
                    Long.valueOf(20L),
                    false,
                    false,
                    null,
                    payload
            ));

            assertEquals(1, sender.sentPackets.size());
            assertEquals(remoteAddress, sender.sentPackets.get(0).target);

            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket audioPacket = decrypt.unprotect(sender.sentPackets.get(0).data);
            assertEquals(0, audioPacket.getPayloadType());
            assertArrayEquals(payload, audioPacket.getPayload());
            assertTrue(audioPacket.getMarker());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldNotTranscodeAacAudioInsidePeerSession() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(0));
        transceiver.setNegotiatedClockRate(Integer.valueOf(8000));
        transceiver.setNegotiatedCodecType(CodecType.G711U);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession(
                "sess-5",
                streamKey,
                peerConnection,
                datagramIo
        );
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50003);

        try {
            session.receive(new byte[0], remoteAddress);
            session.writeInboundFrame(new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.AUDIO,
                    CodecType.AAC,
                    "publisher",
                    streamKey,
                    "audio-aac",
                    Long.valueOf(0L),
                    Long.valueOf(0L),
                    false,
                    true,
                    null,
                    new byte[]{0x12, 0x10}
            ));
            session.writeInboundFrame(new InboundMediaFrame(
                    StreamProtocol.RTMP,
                    TrackType.AUDIO,
                    CodecType.AAC,
                    "publisher",
                    streamKey,
                    "audio-aac",
                    Long.valueOf(20L),
                    Long.valueOf(20L),
                    false,
                    false,
                    null,
                    new byte[]{0x01, 0x02, 0x03, 0x04}
            ));

            assertEquals(0, sender.sentPackets.size());
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

    @Test
    public void shouldResendCachedVideoPacketsWhenNackReceived() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-6", streamKey, peerConnection, datagramIo);
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50004);

        try {
            session.receive(new byte[0], remoteAddress);
            session.writeInboundFrame(h264ConfigFrame(streamKey));
            session.writeInboundFrame(h264Frame(streamKey, true, new byte[]{0x65, 0x11, 0x22, 0x33}, 40L));

            assertEquals(3, sender.sentPackets.size());
            RtcpFeedbackListener listener = transceiver.getSender().getFeedbackListener();
            assertNotNull(listener);

            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket resentTarget = decrypt.unprotect(sender.sentPackets.get(2).data);
            int lostSequenceNumber = resentTarget.getSequenceNumber();
            listener.onGenericNack(transceiver.getSender().getSsrc(), Arrays.asList(Integer.valueOf(lostSequenceNumber)));

            assertEquals(4, sender.sentPackets.size());
            RtpPacket resentPacket = decrypt.unprotect(sender.sentPackets.get(3).data);
            assertEquals(lostSequenceNumber, resentPacket.getSequenceNumber());
            assertArrayEquals(resentTarget.getPayload(), resentPacket.getPayload());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldReplayLatestConfigAndKeyframeWhenPliReceived() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-7", streamKey, peerConnection, datagramIo);
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50005);

        try {
            session.receive(new byte[0], remoteAddress);
            session.writeInboundFrame(h264ConfigFrame(streamKey));
            session.writeInboundFrame(h264Frame(streamKey, true, new byte[]{0x65, 0x11, 0x22, 0x33}, 40L));

            assertEquals(3, sender.sentPackets.size());
            RtcpFeedbackListener listener = transceiver.getSender().getFeedbackListener();
            assertNotNull(listener);

            listener.onPictureLossIndication(transceiver.getSender().getSsrc());

            assertEquals(6, sender.sentPackets.size());
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket replayedSps = decrypt.unprotect(sender.sentPackets.get(3).data);
            RtpPacket replayedPps = decrypt.unprotect(sender.sentPackets.get(4).data);
            RtpPacket replayedKey = decrypt.unprotect(sender.sentPackets.get(5).data);
            assertArrayEquals(new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}, replayedSps.getPayload());
            assertArrayEquals(new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}, replayedPps.getPayload());
            assertArrayEquals(new byte[]{0x65, 0x11, 0x22, 0x33}, replayedKey.getPayload());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldRelayWebRtcSourceH264RtpPacketDirectly() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "browser-cam");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-8", streamKey, peerConnection, datagramIo);
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50006);

        try {
            session.receive(new byte[0], remoteAddress);
            session.writeMediaPacket(webRtcVideoPacket(streamKey, 7, 123456L, true, new byte[]{0x65, 0x11, 0x22, 0x33}));

            assertEquals(1, sender.sentPackets.size());
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket relayedPacket = decrypt.unprotect(sender.sentPackets.get(0).data);
            assertEquals(96, relayedPacket.getPayloadType());
            assertEquals(123456L, relayedPacket.getTimestamp());
            assertTrue(relayedPacket.getMarker());
            assertArrayEquals(new byte[]{0x65, 0x11, 0x22, 0x33}, relayedPacket.getPayload());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldBufferWebRtcSourceRtpUntilRemoteAddressIsBound() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        byte[] keyMaterial = keyMaterial();
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "browser-cam");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-9", streamKey, peerConnection, datagramIo);
        InboundRtpPacket packet = webRtcVideoPacket(streamKey, 8, 123460L, true, new byte[]{0x65, 0x44, 0x55, 0x66});

        try {
            session.writeMediaPacket(packet);
            assertEquals(0, sender.sentPackets.size());

            InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50007);
            session.receive(new byte[0], remoteAddress);

            assertEquals(1, sender.sentPackets.size());
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket relayedPacket = decrypt.unprotect(sender.sentPackets.get(0).data);
            assertEquals(123460L, relayedPacket.getTimestamp());
            assertArrayEquals(new byte[]{0x65, 0x44, 0x55, 0x66}, relayedPacket.getPayload());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldFlushQueuedWebRtcSourceRtpWhenSrtpContextBecomesReady() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "browser-cam");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-10", streamKey, peerConnection, datagramIo);
        byte[] keyMaterial = keyMaterial();

        try {
            session.receive(new byte[0], new InetSocketAddress("127.0.0.1", 50008));
            session.writeMediaPacket(webRtcVideoPacket(streamKey, 10, 123470L, true, new byte[]{0x65, 0x01, 0x02, 0x03}));
            assertEquals(0, sender.sentPackets.size());

            transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
            session.writeMediaPacket(webRtcVideoPacket(streamKey, 11, 123480L, true, new byte[]{0x41, 0x04, 0x05, 0x06}));

            assertEquals(2, sender.sentPackets.size());
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket first = decrypt.unprotect(sender.sentPackets.get(0).data);
            RtpPacket second = decrypt.unprotect(sender.sentPackets.get(1).data);
            assertEquals(123470L, first.getTimestamp());
            assertArrayEquals(new byte[]{0x65, 0x01, 0x02, 0x03}, first.getPayload());
            assertEquals(123480L, second.getTimestamp());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldWaitForInitialWebRtcSourceKeyFrameBeforeRelayingInterFrame() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial(), true));
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "browser-cam");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-10b", streamKey, peerConnection, datagramIo);

        try {
            session.receive(new byte[0], new InetSocketAddress("127.0.0.1", 50018));
            session.writeMediaPacket(webRtcVideoPacket(streamKey, 12, 123490L, true, new byte[]{0x41, 0x11, 0x12, 0x13}));
            session.writeMediaPacket(webRtcVideoPacket(streamKey, 13, 123500L, true, new byte[]{0x65, 0x21, 0x22, 0x23}));

            assertEquals(1, sender.sentPackets.size());
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial(), true), transceiver.getSender().getSsrc());
            RtpPacket packet = decrypt.unprotect(sender.sentPackets.get(0).data);
            assertEquals(123500L, packet.getTimestamp());
            assertArrayEquals(new byte[]{0x65, 0x21, 0x22, 0x23}, packet.getPayload());
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldFlushQueuedVideoStartupFramesWhenSrtpContextBecomesReady() throws Exception {
        RecordingDatagramIoSender sender = new RecordingDatagramIoSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("127.0.0.1", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video"));
        transceiver.setNegotiatedPayloadType(Integer.valueOf(96));
        transceiver.setNegotiatedClockRate(Integer.valueOf(90000));
        transceiver.setNegotiatedCodecType(CodecType.H264);
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        WebRtcPlaybackPeerSession session = new WebRtcPlaybackPeerSession("sess-11", streamKey, peerConnection, datagramIo);
        byte[] keyMaterial = keyMaterial();

        try {
            session.receive(new byte[0], new InetSocketAddress("127.0.0.1", 50009));
            session.writeInboundFrame(h264ConfigFrame(streamKey));
            session.writeInboundFrame(h264Frame(streamKey, true, new byte[]{0x65, 0x21, 0x22, 0x23}, 40L));
            assertEquals(0, sender.sentPackets.size());

            transceiver.getSender().setSrtpContext(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true));
            session.writeInboundFrame(h264Frame(streamKey, false, new byte[]{0x41, 0x31, 0x32, 0x33}, 60L));

            assertTrue(sender.sentPackets.size() >= 3);
            SrtpTransform decrypt = new SrtpTransform(SrtpCryptoContext.fromKeyMaterial(keyMaterial, true), transceiver.getSender().getSsrc());
            RtpPacket first = decrypt.unprotect(sender.sentPackets.get(0).data);
            RtpPacket lastStartup = decrypt.unprotect(sender.sentPackets.get(sender.sentPackets.size() - 2).data);
            assertArrayEquals(new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}, first.getPayload());
            assertArrayEquals(new byte[]{0x65, 0x21, 0x22, 0x23}, lastStartup.getPayload());
        } finally {
            session.close();
        }
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

    private static InboundRtpPacket webRtcVideoPacket(
            StreamKey streamKey,
            int sequenceNumber,
            long timestamp,
            boolean marker,
            byte[] payload
    ) {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) ((marker ? 0x80 : 0x00) | 96);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = 0x01;
        packet[9] = 0x02;
        packet[10] = 0x03;
        packet[11] = 0x04;
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.WEBRTC,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher",
                        streamKey,
                        "video-0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                90000,
                false,
                MediaPacketTransport.UDP,
                Integer.valueOf(18081),
                null
        );
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
