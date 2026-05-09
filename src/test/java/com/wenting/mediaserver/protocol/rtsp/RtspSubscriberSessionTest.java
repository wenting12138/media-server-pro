package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.AudioTrack;
import com.wenting.mediaserver.core.track.VideoTrack;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldFallbackToControlChannelAddressWhenTrackConnectionAddressIsUnspecified() {
        EmbeddedChannel controlChannel = new EmbeddedChannel() {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 8554);
            }
        };

        AtomicReference<InetSocketAddress> remoteAddress = new AtomicReference<InetSocketAddress>();

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                controlChannel,
                (packet, port, address) -> {
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
        subscriber.track(new VideoTrack("trackID=0", CodecType.H264, "0.0.0.0"));

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
                        null,
                        new byte[]{0x11, 0x22, 0x33}
                ),
                9000,
                false,
                null,
                20000,
                2
        ));

        assertNotNull(remoteAddress.get());
        assertEquals("127.0.0.1", remoteAddress.get().getAddress().getHostAddress());
        assertEquals(5000, remoteAddress.get().getPort());
        controlChannel.finishAndReleaseAll();
    }

    @Test
    void shouldSendVideoAndAudioFramesOverTheirNegotiatedUdpPorts() {
        EmbeddedChannel controlChannel = new EmbeddedChannel() {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 8554);
            }
        };

        List<InboundRtpPacket> sentPackets = new ArrayList<InboundRtpPacket>();
        List<Integer> localPorts = new ArrayList<Integer>();
        List<InetSocketAddress> remoteAddresses = new ArrayList<InetSocketAddress>();

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                controlChannel,
                (packet, port, address) -> {
                    sentPackets.add(packet);
                    localPorts.add(Integer.valueOf(port));
                    remoteAddresses.add(address);
                    return true;
                }
        );
        subscriber.transport("video-h264", new RtspTransport(
                RtspTransportMode.RTP_UDP,
                5000,
                5001,
                20000,
                20001,
                null,
                null,
                "RTP/AVP;unicast;client_port=5000-5001;server_port=20000-20001"
        ));
        subscriber.transport("audio-aac", new RtspTransport(
                RtspTransportMode.RTP_UDP,
                5002,
                5003,
                20002,
                20003,
                null,
                null,
                "RTP/AVP;unicast;client_port=5002-5003;server_port=20002-20003"
        ));
        subscriber.track(new VideoTrack("video-h264", CodecType.H264, "127.0.0.1", 90000));
        subscriber.track(new AudioTrack("audio-aac", CodecType.AAC, "127.0.0.1", 48000, 2, 128000));

        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
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
        ));
        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "video-h264",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                new byte[]{
                        0x00, 0x00, 0x00, 0x03,
                        0x65, 0x11, 0x22
                }
        ));
        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                CodecType.AAC,
                "publisher",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "audio-aac",
                Long.valueOf(120L),
                Long.valueOf(120L),
                false,
                false,
                null,
                new byte[]{0x55, 0x66}
        ));

        assertTrue(sentPackets.size() >= 4);
        assertEquals(Integer.valueOf(20000), localPorts.get(0));
        assertEquals(5000, remoteAddresses.get(0).getPort());
        assertEquals(Integer.valueOf(20002), localPorts.get(sentPackets.size() - 1));
        assertEquals(5002, remoteAddresses.get(sentPackets.size() - 1).getPort());
        assertEquals(97, sentPackets.get(sentPackets.size() - 1).frame().payload()[1] & 0x7F);
        controlChannel.finishAndReleaseAll();
    }

    @Test
    void shouldAdvanceRtpTimestampAcrossConsecutiveVideoFrames() {
        EmbeddedChannel controlChannel = new EmbeddedChannel() {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 8554);
            }
        };

        List<InboundRtpPacket> sentPackets = new ArrayList<InboundRtpPacket>();

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                controlChannel,
                (packet, port, address) -> {
                    sentPackets.add(packet);
                    return true;
                }
        );
        subscriber.transport("video-h264", new RtspTransport(
                RtspTransportMode.RTP_UDP,
                5000,
                5001,
                20000,
                20001,
                null,
                null,
                "RTP/AVP;unicast;client_port=5000-5001;server_port=20000-20001"
        ));
        subscriber.track(new VideoTrack("video-h264", CodecType.H264, "127.0.0.1", 90000));

        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
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
        ));
        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "video-h264",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                new byte[]{
                        0x00, 0x00, 0x00, 0x03,
                        0x65, 0x11, 0x22
                }
        ));
        subscriber.writeInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "video-h264",
                Long.valueOf(140L),
                Long.valueOf(140L),
                true,
                false,
                null,
                new byte[]{
                        0x00, 0x00, 0x00, 0x03,
                        0x65, 0x33, 0x44
                }
        ));

        InboundRtpPacket firstVideo = sentPackets.get(sentPackets.size() - 2);
        InboundRtpPacket secondVideo = sentPackets.get(sentPackets.size() - 1);
        long firstTimestamp = readRtpTimestamp(firstVideo.frame().payload());
        long secondTimestamp = readRtpTimestamp(secondVideo.frame().payload());

        assertEquals(3600L, secondTimestamp - firstTimestamp);
        controlChannel.finishAndReleaseAll();
    }

    private static long readRtpTimestamp(byte[] payload) {
        return ((long) (payload[4] & 0xFF) << 24)
                | ((long) (payload[5] & 0xFF) << 16)
                | ((long) (payload[6] & 0xFF) << 8)
                | (long) (payload[7] & 0xFF);
    }
}
