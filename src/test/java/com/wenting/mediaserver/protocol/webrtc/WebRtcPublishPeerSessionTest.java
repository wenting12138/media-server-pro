package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpReceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCSessionDescription;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WebRtcPublishPeerSessionTest {

    @Test
    public void shouldDelayInboundPublishUntilIngestActivated() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);

        SessionDatagramIo datagramIo = new SessionDatagramIo(
                new InetSocketAddress("127.0.0.1", 18081),
                new NoopDatagramIoSender()
        );
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        peerConnection.setRemoteDescription(new RTCSessionDescription("offer", publishOfferWithH264()));
        peerConnection.setLocalDescription(peerConnection.createAnswer().get());

        WebRtcPublishPeerSession session = new WebRtcPublishPeerSession(
                "publish-test-1",
                streamKey,
                peerConnection,
                datagramIo,
                registry
        );
        session.attachPublishedStream(stream);

        try {
            RTCRtpReceiver receiver = firstVideoReceiver(peerConnection);
            assertNotNull(receiver.getOnPacket());
            assertNull(registry.findPublishedStream(streamKey));

            receiver.getOnPacket().accept(videoPacket(0x01020304L, 3000L, 1));
            assertNull(stream.latestTrackSsrc("video-0"));

            session.activateIngest();
            receiver.getOnPacket().accept(videoPacket(0x01020304L, 6000L, 2));

            assertEquals(0x01020304L, stream.latestTrackSsrc("video-0"));
        } finally {
            session.close();
        }
    }

    @Test
    public void shouldRegisterPublishedStreamOnlyAfterPeerConnectionConnected() throws Exception {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam02");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);

        SessionDatagramIo datagramIo = new SessionDatagramIo(
                new InetSocketAddress("127.0.0.1", 18081),
                new NoopDatagramIoSender()
        );
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        peerConnection.setRemoteDescription(new RTCSessionDescription("offer", publishOfferWithH264()));
        peerConnection.setLocalDescription(peerConnection.createAnswer().get());

        WebRtcPublishPeerSession session = new WebRtcPublishPeerSession(
                "publish-test-2",
                streamKey,
                peerConnection,
                datagramIo,
                registry
        );
        session.attachPublishedStream(stream);
        session.installLifecycleCleanup(() -> { });

        try {
            assertNull(registry.findPublishedStream(streamKey));

            emitConnectionState(peerConnection, RTCPeerConnection.ConnectionState.CONNECTED);

            assertEquals(stream, registry.findPublishedStream(streamKey));
        } finally {
            session.close();
        }
    }

    private static RTCRtpReceiver firstVideoReceiver(RTCPeerConnection peerConnection) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (transceiver != null
                    && transceiver.getKind() == MediaStreamTrack.Kind.VIDEO
                    && transceiver.getReceiver() != null) {
                return transceiver.getReceiver();
            }
        }
        return null;
    }

    private static RtpPacket videoPacket(long ssrc, long timestamp, int sequenceNumber) {
        return new RtpPacket(
                2,
                false,
                false,
                0,
                true,
                96,
                sequenceNumber,
                timestamp,
                ssrc,
                null,
                new byte[]{0x65, 0x11, 0x22, 0x33}
        );
    }

    private static void emitConnectionState(RTCPeerConnection peerConnection,
                                            RTCPeerConnection.ConnectionState state) throws Exception {
        Method method = RTCPeerConnection.class.getDeclaredMethod("setConnectionState",
                RTCPeerConnection.ConnectionState.class);
        method.setAccessible(true);
        method.invoke(peerConnection, state);
    }

    private static String publishOfferWithH264() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=-\r\n"
                + "t=0 0\r\n"
                + "a=group:BUNDLE 0\r\n"
                + "a=ice-ufrag:abcd\r\n"
                + "a=ice-pwd:abcdefghijklmnopqrstuv\r\n"
                + "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00\r\n"
                + "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=mid:0\r\n"
                + "a=setup:actpass\r\n"
                + "a=sendonly\r\n"
                + "a=rtcp-mux\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static final class NoopDatagramIoSender implements DatagramIoSender {
        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
