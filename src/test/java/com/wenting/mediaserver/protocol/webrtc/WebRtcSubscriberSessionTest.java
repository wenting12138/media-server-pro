package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsClientHelloParserTest;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsClientFlightParserTest;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsServerTransport;
import com.wenting.mediaserver.protocol.webrtc.dtls.WebRtcCertificateManager;
import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebRtcSubscriberSessionTest {

    @Test
    void shouldNotSendFramesBeforeDtlsSrtpIsReady() {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        WebRtcPeerSession peerSession = peerSession("peer-not-ready");
        peerSession.remoteAddress(new InetSocketAddress("127.0.0.1", 50000));
        peerSession.dtlsServerTransport(new DtlsServerTransport(peerSession.sessionId(), new WebRtcCertificateManager().certificate()));
        WebRtcSubscriberSession subscriberSession = new WebRtcSubscriberSession(peerSession, sender);

        subscriberSession.writeInboundFrame(h264ConfigFrame());
        subscriberSession.writeInboundFrame(h264KeyFrame());

        assertTrue(sender.payloads.isEmpty());
    }

    @Test
    void shouldSendStartupH264FramesAfterDtlsSrtpBecomesReady() {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        WebRtcPeerSession peerSession = peerSession("peer-ready");
        DtlsServerTransport dtlsServerTransport = new DtlsServerTransport(peerSession.sessionId(), new WebRtcCertificateManager().certificate());
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 50002);
        dtlsServerTransport.handleClientHello(DtlsClientHelloParserTest.sampleClientHello(), remoteAddress);
        dtlsServerTransport.markServerHelloSent();
        dtlsServerTransport.handleClientFlight(DtlsClientFlightParserTest.sampleClientFlight(), remoteAddress);
        peerSession.remoteAddress(remoteAddress);
        peerSession.dtlsServerTransport(dtlsServerTransport);
        WebRtcSubscriberSession subscriberSession = new WebRtcSubscriberSession(peerSession, sender);

        subscriberSession.writeInboundFrame(h264ConfigFrame());
        subscriberSession.writeInboundFrame(h264KeyFrame());

        assertFalse(sender.payloads.isEmpty());
        assertEquals(remoteAddress, sender.recipients.get(0));
        assertEquals(0x80, sender.payloads.get(0)[0] & 0xFF);
        assertEquals(97, sender.payloads.get(0)[1] & 0x7F);
        assertTrue(sender.payloads.size() >= 3);
    }

    private static WebRtcPeerSession peerSession(String sessionId) {
        IceAgent iceAgent = new IceAgent("localufrag", "localpwd");
        iceAgent.addHostCandidate("127.0.0.1", 18081);
        return new WebRtcPeerSession(
                sessionId,
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "offer",
                "answer",
                "localufrag",
                "localpwd",
                "AA:BB",
                iceAgent,
                System.currentTimeMillis()
        );
    }

    private static InboundMediaFrame h264ConfigFrame() {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
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

    private static InboundMediaFrame h264KeyFrame() {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "video-h264",
                Long.valueOf(40L),
                Long.valueOf(40L),
                true,
                false,
                null,
                new byte[]{
                        0x00, 0x00, 0x00, 0x04, 0x65, 0x11, 0x22, 0x33
                }
        );
    }

    private static final class RecordingDatagramSender implements WebRtcDatagramSender {

        private final List<byte[]> payloads = new ArrayList<byte[]>();
        private final List<InetSocketAddress> recipients = new ArrayList<InetSocketAddress>();

        @Override
        public void send(byte[] payload, InetSocketAddress remoteAddress) {
            payloads.add(payload);
            recipients.add(remoteAddress);
        }
    }
}
