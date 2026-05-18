package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCSessionDescription;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunConstants;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIoSender;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class WebRtcUdpPacketHandlerTest {

    @Test
    public void shouldBindRemoteAddressFromStunUsernameToSession() throws Exception {
        assertBindRemoteAddressFromStunUsername(false);
    }

    @Test
    public void shouldBindRemoteAddressFromStandardStunUsernameOrder() throws Exception {
        assertBindRemoteAddressFromStunUsername(true);
    }

    @Test
    public void shouldRouteDtlsPacketAfterStunBindsRemoteAddress() throws Exception {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("192.168.3.52", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerWithH264());
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);

            ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                    "sess-1",
                    new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                    peerConnection,
                    datagramIo
            );
            sessionManager.register(session);

            final byte[][] received = new byte[1][];
            datagramIo.setPacketHandler((data, remoteAddress) -> received[0] = data);

            InetSocketAddress remoteAddress = new InetSocketAddress("192.168.3.60", 50000);
            new WebRtcUdpPacketHandler(sessionManager).onPacket(bindingRequest(peerConnection, true).encode(), remoteAddress);

            byte[] dtlsClientHello = new byte[]{22, (byte) 0xFE, (byte) 0xFD, 0, 0, 0, 0, 0};
            new WebRtcUdpPacketHandler(sessionManager).onPacket(dtlsClientHello, remoteAddress);

            assertArrayEquals(dtlsClientHello, received[0]);
        } finally {
            sessionManager.close();
        }
    }

    @Test
    public void shouldBindRemoteAddressToPublishSessionFromStunUsername() throws Exception {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("192.168.3.52", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerWithH264());
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);

            StreamRegistry registry = new StreamRegistry();
            StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam01");
            DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);
            registry.registerPublishedStream(streamKey, publishedStream);
            WebRtcPublishPeerSession session = new WebRtcPublishPeerSession(
                    "pub-1",
                    streamKey,
                    peerConnection,
                    datagramIo,
                    registry
            );
            session.attachPublishedStream(publishedStream);
            sessionManager.register(session);

            InetSocketAddress remoteAddress = new InetSocketAddress("192.168.3.60", 50000);
            new WebRtcUdpPacketHandler(null, sessionManager).onPacket(bindingRequest(peerConnection, true).encode(), remoteAddress);

            assertSame(session, sessionManager.findByRemoteAddress(remoteAddress));
        } finally {
            sessionManager.close();
        }
    }

    @Test
    public void shouldRouteDtlsPacketToPublishSessionAfterStunBind() throws Exception {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("192.168.3.52", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcPublishSessionManager sessionManager = new WebRtcPublishSessionManager();
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerWithH264());
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);

            StreamRegistry registry = new StreamRegistry();
            StreamKey streamKey = new StreamKey(StreamProtocol.WEBRTC, "live", "cam01");
            DefaultPublishedStream publishedStream = new DefaultPublishedStream(streamKey);
            registry.registerPublishedStream(streamKey, publishedStream);
            WebRtcPublishPeerSession session = new WebRtcPublishPeerSession(
                    "pub-1",
                    streamKey,
                    peerConnection,
                    datagramIo,
                    registry
            );
            session.attachPublishedStream(publishedStream);
            sessionManager.register(session);

            final byte[][] received = new byte[1][];
            datagramIo.setPacketHandler((data, remoteAddress) -> received[0] = data);

            InetSocketAddress remoteAddress = new InetSocketAddress("192.168.3.60", 50000);
            WebRtcUdpPacketHandler packetHandler = new WebRtcUdpPacketHandler(null, sessionManager);
            packetHandler.onPacket(bindingRequest(peerConnection, true).encode(), remoteAddress);

            byte[] dtlsClientHello = new byte[]{22, (byte) 0xFE, (byte) 0xFD, 0, 0, 0, 0, 0};
            packetHandler.onPacket(dtlsClientHello, remoteAddress);

            assertArrayEquals(dtlsClientHello, received[0]);
        } finally {
            sessionManager.close();
        }
    }

    private void assertBindRemoteAddressFromStunUsername(boolean localUfragFirst) throws Exception {
        RecordingDatagramSender sender = new RecordingDatagramSender();
        SessionDatagramIo datagramIo = new SessionDatagramIo(new InetSocketAddress("192.168.3.52", 18081), sender);
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        WebRtcSessionManager sessionManager = new WebRtcSessionManager();
        try {
            RTCSessionDescription offer = new RTCSessionDescription("offer", offerWithH264());
            peerConnection.setRemoteDescription(offer);
            RTCSessionDescription answer = peerConnection.createAnswer().get();
            peerConnection.setLocalDescription(answer);

            ServerWebRtcPeerSession session = new ServerWebRtcPeerSession(
                    "sess-1",
                    new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                    peerConnection,
                    datagramIo
            );
            sessionManager.register(session);

            StunMessage request = bindingRequest(peerConnection, localUfragFirst);

            InetSocketAddress remoteAddress = new InetSocketAddress("192.168.3.60", 50000);
            new WebRtcUdpPacketHandler(sessionManager).onPacket(request.encode(), remoteAddress);

            assertSame(session, sessionManager.findByRemoteAddress(remoteAddress));
        } finally {
            sessionManager.close();
        }
    }

    private static String offerWithH264() {
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
                + "a=sendrecv\r\n"
                + "a=rtpmap:96 H264/90000\r\n";
    }

    private static StunMessage bindingRequest(RTCPeerConnection peerConnection, boolean localUfragFirst) {
        byte[] txId = new byte[12];
        String usernameValue = localUfragFirst
                ? peerConnection.getLocalUfrag() + ":abcd"
                : "abcd:" + peerConnection.getLocalUfrag();
        byte[] username = usernameValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<StunMessage.Attribute> attrs = new ArrayList<StunMessage.Attribute>();
        attrs.add(new StunMessage.Attribute(StunConstants.ATTR_USERNAME, username));
        return new StunMessage(
                StunConstants.METHOD_BINDING,
                com.wenting.mediaserver.protocol.webrtc.core.stun.StunClass.REQUEST,
                txId,
                attrs
        );
    }

    private static final class RecordingDatagramSender implements DatagramIoSender {
        private int sendCount;

        @Override
        public CompletableFuture<Void> send(byte[] data, InetSocketAddress target) {
            sendCount++;
            return CompletableFuture.completedFuture(null);
        }
    }
}
