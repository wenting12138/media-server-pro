package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.VideoTrack;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RtspSubscriberSessionTest {

    @Test
    void shouldSendUdpPacketFromNegotiatedServerPortToClientPort() {
        EmbeddedChannel controlChannel = new EmbeddedChannel() {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 8554);
            }
        };

        AtomicInteger localPort = new AtomicInteger(-1);
        AtomicReference<InetSocketAddress> remoteAddress = new AtomicReference<InetSocketAddress>();
        AtomicReference<InboundRtpPacket> packetRef = new AtomicReference<InboundRtpPacket>();

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                controlChannel,
                (packet, port, address) -> {
                    packetRef.set(packet);
                    localPort.set(port);
                    remoteAddress.set(address);
                    return true;
                }
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_UDP,
                5000,
                5001,
                20000,
                20001,
                null,
                null,
                "RTP/AVP;unicast;client_port=5000-5001;server_port=20000-20001"
        ));
        subscriber.track(new VideoTrack("trackID=0", CodecType.H264, "192.168.1.10"));

        subscriber.writeMediaPacket(new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        remoteAddress.get(),
                        new byte[]{0x11, 0x22, 0x33}
                ),
                9000,
                false,
                null,
                localPort.get(),
                2
        ));

        assertEquals(20000, localPort.get());
        assertNotNull(remoteAddress.get());
        assertEquals(5000, remoteAddress.get().getPort());
        assertEquals("192.168.1.10", remoteAddress.get().getHostString());
        assertNotNull(packetRef.get());
        assertEquals(3, packetRef.get().frame().payloadLength());
        controlChannel.finishAndReleaseAll();
    }
}
