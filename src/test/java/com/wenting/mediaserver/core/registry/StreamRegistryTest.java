package com.wenting.mediaserver.core.registry;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import com.wenting.mediaserver.protocol.rtsp.RtspUdpBinding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StreamRegistryTest {

    @Test
    void shouldRouteUdpPacketToPublishedStreamByLocalPort() {
        RtspSessionManager sessionManager = new RtspSessionManager();
        StreamRegistry registry = new StreamRegistry(sessionManager);
        StreamKey key = new StreamKey(StreamProtocol.RTSP, "live", "camera01");
        RecordingPublishedStream stream = new RecordingPublishedStream();
        registry.registerPublishedStream(key, stream);
        registry.bindUdpPort(20000, new RtspUdpBinding("session-1", key, "trackID=0", false));
        RtspSession session = sessionManager.register(new RtspSession("session-1"));
        session.streamKey(key);
        session.sdpOrigin("v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=0\r\n");

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{0x11, 0x22, 0x33});
        registry.onUdpPacket(20000, new InetSocketAddress("127.0.0.1", 5004), payload);

        assertEquals(20000, stream.localPort);
        assertEquals(5004, stream.remoteAddress.getPort());
        assertEquals(3, stream.packetSize);
        assertEquals("session-1", stream.packet.frame().sessionId());
        assertEquals("trackID=0", stream.packet.frame().trackId());
        assertSame(TrackType.VIDEO, stream.packet.frame().trackType());
        assertSame(CodecType.H264, stream.packet.frame().codecType());
        assertEquals(90000, stream.packet.clockRate());
    }

    private static final class RecordingPublishedStream implements IPublishedStream {
        private InboundRtpPacket packet;
        private int localPort;
        private InetSocketAddress remoteAddress;
        private int packetSize;

        @Override
        public StreamProtocol getProtocol() {
            return StreamProtocol.RTSP;
        }

        @Override
        public void onInboundFrame(InboundMediaFrame frame) {
        }

        @Override
        public void onInboundRtpPacket(InboundRtpPacket packet) {
            this.packet = packet;
            this.localPort = packet.localPort().intValue();
            this.remoteAddress = packet.frame().remoteAddress();
            this.packetSize = packet.frame().payloadLength();
        }

        @Override
        public void addSubscriber(MediaSubscriberAdapter subscriber) {
        }

        @Override
        public void removeSubscriber(String sessionId) {
        }
    }
}
