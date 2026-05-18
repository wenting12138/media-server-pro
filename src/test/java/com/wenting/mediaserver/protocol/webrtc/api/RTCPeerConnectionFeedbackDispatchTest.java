package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtcpTransform;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RTCPeerConnectionFeedbackDispatchTest {

    @Test
    void shouldDispatchPliAndNackToMatchingSender() throws Exception {
        StubDatagramIo datagramIo = new StubDatagramIo();
        RTCPeerConnection peerConnection = new RTCPeerConnection(datagramIo);
        try {
            RTCRtpTransceiver transceiver = peerConnection.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video1"));
            byte[] keyMaterial = new byte[60];
            for (int i = 0; i < keyMaterial.length; i++) {
                keyMaterial[i] = (byte) (i + 1);
            }
            SrtpCryptoContext recvCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, true);
            transceiver.getReceiver().setSrtpContext(recvCtx);
            setField(peerConnection, "inboundSrtcpContext", recvCtx);

            AtomicLong pliSsrc = new AtomicLong(-1L);
            AtomicReference<List<Integer>> nackSeqs = new AtomicReference<List<Integer>>();
            transceiver.getSender().setFeedbackListener(new RtcpFeedbackListener() {
                @Override
                public void onPictureLossIndication(long mediaSsrc) {
                    pliSsrc.set(mediaSsrc);
                }

                @Override
                public void onGenericNack(long mediaSsrc, List<Integer> lostSequenceNumbers) {
                    nackSeqs.set(lostSequenceNumbers);
                }
            });

            SrtcpTransform transform = new SrtcpTransform(recvCtx);
            datagramIo.dispatch(transform.protect(new byte[]{
                    (byte) 0x81, (byte) 206, 0x00, 0x02,
                    0x01, 0x02, 0x03, 0x04,
                    (byte) ((transceiver.getSender().getSsrc() >>> 24) & 0xFF),
                    (byte) ((transceiver.getSender().getSsrc() >>> 16) & 0xFF),
                    (byte) ((transceiver.getSender().getSsrc() >>> 8) & 0xFF),
                    (byte) (transceiver.getSender().getSsrc() & 0xFF)
            }));

            datagramIo.dispatch(transform.protect(new byte[]{
                    (byte) 0x81, (byte) 205, 0x00, 0x03,
                    0x01, 0x02, 0x03, 0x04,
                    (byte) ((transceiver.getSender().getSsrc() >>> 24) & 0xFF),
                    (byte) ((transceiver.getSender().getSsrc() >>> 16) & 0xFF),
                    (byte) ((transceiver.getSender().getSsrc() >>> 8) & 0xFF),
                    (byte) (transceiver.getSender().getSsrc() & 0xFF),
                    0x12, 0x34, 0x00, 0x03
            }));

            assertEquals(transceiver.getSender().getSsrc(), pliSsrc.get());
            assertEquals(Arrays.asList(Integer.valueOf(0x1234), Integer.valueOf(0x1235), Integer.valueOf(0x1236)), nackSeqs.get());
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

        void dispatch(byte[] data) {
            packetHandler.onPacket(data, new InetSocketAddress("127.0.0.1", 50000));
        }
    }
}
