package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.report.AvSyncSnapshot;
import com.wenting.mediaserver.core.publish.report.RtcpTrackStats;
import com.wenting.mediaserver.protocol.rtsp.RtspSubscriberAdapter;
import com.wenting.mediaserver.protocol.rtsp.RtspSubscriberSession;
import com.wenting.mediaserver.protocol.rtsp.RtspTransport;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPublishedStreamTest {

    @Test
    void shouldAddSubscriberAndFanoutInterleavedPacket() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                4,
                5,
                "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));
        assertEquals(1, stream.subscriberCount());

        stream.onInboundRtpPacket(new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[]{
                                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                                0x01, 0x02, 0x03, 0x04,
                                0x11, 0x22, 0x33, 0x44,
                                0x65, 0x11, 0x22
                        }
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        ));

        ByteBuf outbound = channel.readOutbound();
        assertNotNull(outbound);
        assertEquals('$', outbound.readByte());
        assertEquals(4, outbound.readUnsignedByte());
        assertEquals(15, outbound.readUnsignedShort());
        assertEquals(0x80, outbound.readUnsignedByte());
        assertEquals(0xE0, outbound.readUnsignedByte());
        assertEquals(0x12, outbound.readUnsignedByte());
        assertEquals(0x34, outbound.readUnsignedByte());
        assertEquals(0x01, outbound.readUnsignedByte());
        assertEquals(0x02, outbound.readUnsignedByte());
        assertEquals(0x03, outbound.readUnsignedByte());
        assertEquals(0x04, outbound.readUnsignedByte());
        assertEquals(0x11, outbound.readUnsignedByte());
        assertEquals(0x22, outbound.readUnsignedByte());
        assertEquals(0x33, outbound.readUnsignedByte());
        assertEquals(0x44, outbound.readUnsignedByte());
        assertEquals(0x65, outbound.readUnsignedByte());
        assertEquals(0x11, outbound.readUnsignedByte());
        assertEquals(0x22, outbound.readUnsignedByte());
        outbound.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFanoutProtocolNeutralInboundFrame() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        AtomicInteger frameCount = new AtomicInteger();
        AtomicReference<InboundMediaFrame> frameRef = new AtomicReference<InboundMediaFrame>();

        stream.addSubscriber(new MediaSubscriberAdapter() {
            @Override
            public String sessionId() {
                return "frame-subscriber";
            }

            @Override
            public boolean acceptsTrack(String trackId) {
                return "video-main".equals(trackId);
            }

            @Override
            public void writeMediaPacket(InboundRtpPacket packet) {
            }

            @Override
            public void writeInboundFrame(InboundMediaFrame frame) {
                frameCount.incrementAndGet();
                frameRef.set(frame);
            }
        });

        InboundMediaFrame frame = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "video-main",
                Long.valueOf(1000L),
                Long.valueOf(900L),
                true,
                false,
                null,
                new byte[]{0x11, 0x22, 0x33}
        );

        stream.onInboundFrame(frame);

        assertEquals(1, frameCount.get());
        assertNotNull(frameRef.get());
        assertEquals(StreamProtocol.RTMP, frameRef.get().sourceProtocol());
        assertEquals("video-main", frameRef.get().trackId());
        assertEquals(3, frameRef.get().payloadLength());
    }

    @Test
    void shouldCacheRtmpConfigFramesViaFrameHandlers() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));

        InboundMediaFrame videoConfig = new InboundMediaFrame(
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
                new byte[]{0x01, 0x64}
        );
        InboundMediaFrame audioConfig = new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                CodecType.AAC,
                "publisher-1",
                new StreamKey(StreamProtocol.RTMP, "live", "cam01"),
                "audio-aac",
                Long.valueOf(0L),
                Long.valueOf(0L),
                false,
                true,
                null,
                new byte[]{0x12, 0x10}
        );

        stream.onInboundFrame(videoConfig);
        stream.onInboundFrame(audioConfig);

        PublishedTrackContext videoTrack = stream.publishedTrackContext("video-h264");
        PublishedTrackContext audioTrack = stream.publishedTrackContext("audio-aac");
        assertNotNull(videoTrack);
        assertNotNull(audioTrack);
        assertEquals(videoConfig, videoTrack.latestConfigFrame());
        assertEquals(videoConfig, videoTrack.latestKeyFrame());
        assertEquals(audioConfig, audioTrack.latestConfigFrame());
    }

    @Test
    void shouldAcceptInboundRtpPacketEntry() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[]{
                                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                                0x01, 0x02, 0x03, 0x04,
                                0x11, 0x22, 0x33, 0x44,
                                0x65, 0x11, 0x22
                        }
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        ));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(1, snapshot.size());
        assertEquals(0x1234, readSequenceNumber(snapshot.get(0).frame().payload()));
    }

    @Test
    void shouldWaitForKeyFrameBeforeStartingNewSubscriber() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                4,
                5,
                "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));
        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x05,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x33, 0x44
        }));
        assertNull(channel.readOutbound());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x36,
                0x01, 0x02, 0x03, 0x06,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        ByteBuf outbound = channel.readOutbound();
        assertNotNull(outbound);
        assertEquals('$', outbound.readByte());
        assertEquals(4, outbound.readUnsignedByte());
        assertEquals(15, outbound.readUnsignedShort());
        outbound.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFlushCachedGopImmediatelyWhenSubscriberJoinsLate() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x05,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x33, 0x44
        }));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                4,
                5,
                "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        first.release();
        second.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFlushSpsAndPpsBeforeGopWhenSubscriberJoinsLate() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x67, 0x00, 0x10,
                0x00, 0x00, 0x00, 0x10,
                0x11, 0x22, 0x33, 0x44,
                0x67, 0x42, 0x00
        }));
        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x68, 0x00, 0x11,
                0x00, 0x00, 0x00, 0x11,
                0x11, 0x22, 0x33, 0x44,
                0x68, (byte) 0xCE, 0x06
        }));
        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                4,
                5,
                "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        ByteBuf sps = channel.readOutbound();
        ByteBuf pps = channel.readOutbound();
        ByteBuf idr = channel.readOutbound();
        assertNotNull(sps);
        assertNotNull(pps);
        assertNotNull(idr);
        assertEquals(0x67, readNalType(sps));
        assertEquals(0x68, readNalType(pps));
        assertEquals(0x65, readNalType(idr));
        sps.release();
        pps.release();
        idr.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStartNewGopOnH264KeyFrameAndAppendFollowingPackets() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x05,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x33, 0x44
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.get(0).frame().payload()[12] == (byte) 0x65);
        assertTrue(snapshot.get(1).frame().payload()[12] == (byte) 0x41);
    }

    @Test
    void shouldReorderUdpRtpPacketsBeforeUpdatingGop() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(udpVideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(udpVideoPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x36,
                0x01, 0x02, 0x03, 0x06,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x55, 0x66
        }));
        stream.onInboundRtpPacket(udpVideoPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x05,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x33, 0x44
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(3, snapshot.size());
        assertEquals(0x1234, readSequenceNumber(snapshot.get(0).frame().payload()));
        assertEquals(0x1235, readSequenceNumber(snapshot.get(1).frame().payload()));
        assertEquals(0x1236, readSequenceNumber(snapshot.get(2).frame().payload()));
    }

    @Test
    void shouldNotAppendNonKeyFrameBeforeGopStarts() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x05,
                0x11, 0x22, 0x33, 0x44,
                0x41, 0x33, 0x44
        }));

        assertTrue(stream.currentGopSnapshot().isEmpty());
    }

    @Test
    void shouldCacheAudioOnlyAfterVideoGopStarts() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(audioPacket(new byte[]{
                (byte) 0x80, (byte) 0x61, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x11, 0x22, 0x33, 0x44,
                0x11, 0x22
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(audioPacket(new byte[]{
                (byte) 0x80, (byte) 0x61, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x02,
                0x11, 0x22, 0x33, 0x44,
                0x33, 0x44
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(TrackType.VIDEO, snapshot.get(0).frame().trackType());
        assertEquals(TrackType.AUDIO, snapshot.get(1).frame().trackType());
    }

    @Test
    void shouldCacheMpeg4GenericOnlyAfterVideoGopStarts() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(mpeg4GenericPacket(new byte[]{
                (byte) 0x80, 0x61, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x11, 0x22, 0x33, 0x44,
                0x10, 0x20
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(mpeg4GenericPacket(new byte[]{
                (byte) 0x80, 0x61, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x02,
                0x11, 0x22, 0x33, 0x44,
                0x30, 0x40
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(TrackType.VIDEO, snapshot.get(0).frame().trackType());
        assertEquals(CodecType.MPEG4_GENERIC, snapshot.get(1).frame().codecType());
    }

    @Test
    void shouldCacheG711OnlyAfterVideoGopStarts() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(g711Packet(new byte[]{
                (byte) 0x80, 0x08, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x11, 0x22, 0x33, 0x44,
                0x55, 0x66
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(g711Packet(new byte[]{
                (byte) 0x80, 0x08, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x02,
                0x11, 0x22, 0x33, 0x44,
                0x77, (byte) 0x88
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(TrackType.VIDEO, snapshot.get(0).frame().trackType());
        assertEquals(CodecType.G711A, snapshot.get(1).frame().codecType());
    }

    @Test
    void shouldResolveH265PayloadHandlerWithoutBreakingRtpIngest() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H265,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=2",
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[]{
                                (byte) 0x80, (byte) 0x60, 0x12, 0x40,
                                0x01, 0x02, 0x03, 0x07,
                                0x11, 0x22, 0x33, 0x55,
                                0x26, 0x01, 0x02
                        }
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(4)
        ));

        assertTrue(stream.currentGopSnapshot().isEmpty());
    }

    @Test
    void shouldFlushH265ParameterSetsBeforeGopWhenSubscriberJoinsLate() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x10,
                0x00, 0x00, 0x00, 0x10,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x11,
                0x00, 0x00, 0x00, 0x11,
                0x11, 0x22, 0x33, 0x44,
                0x42, 0x01, 0x02
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x12,
                0x00, 0x00, 0x00, 0x12,
                0x11, 0x22, 0x33, 0x44,
                0x44, 0x01, 0x03
        }));
        stream.onInboundRtpPacket(h265IdrPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x13,
                0x00, 0x00, 0x00, 0x13,
                0x11, 0x22, 0x33, 0x44,
                0x26, 0x01, 0x55
        }));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-h265",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=2", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                6,
                7,
                "RTP/AVP/TCP;unicast;interleaved=6-7"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        ByteBuf vps = channel.readOutbound();
        ByteBuf sps = channel.readOutbound();
        ByteBuf pps = channel.readOutbound();
        ByteBuf idr = channel.readOutbound();
        assertNotNull(vps);
        assertNotNull(sps);
        assertNotNull(pps);
        assertNotNull(idr);
        assertEquals(32, readH265NalType(vps));
        assertEquals(33, readH265NalType(sps));
        assertEquals(34, readH265NalType(pps));
        assertEquals(19, readH265NalType(idr));
        vps.release();
        sps.release();
        pps.release();
        idr.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFlushH265ApParameterSetPacketBeforeGopWhenSubscriberJoinsLate() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x20,
                0x00, 0x00, 0x00, 0x20,
                0x11, 0x22, 0x33, 0x44,
                0x60, 0x01,
                0x00, 0x03, 0x40, 0x01, 0x01,
                0x00, 0x03, 0x42, 0x01, 0x02,
                0x00, 0x03, 0x44, 0x01, 0x03
        }));
        stream.onInboundRtpPacket(h265IdrPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x21,
                0x00, 0x00, 0x00, 0x21,
                0x11, 0x22, 0x33, 0x44,
                0x26, 0x01, 0x55
        }));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-h265-ap",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=2", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                6,
                7,
                "RTP/AVP/TCP;unicast;interleaved=6-7"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        ByteBuf ap = channel.readOutbound();
        ByteBuf idr = channel.readOutbound();
        assertNotNull(ap);
        assertNotNull(idr);
        assertEquals(48, readH265NalType(ap));
        assertEquals(19, readH265NalType(idr));
        assertNull(channel.readOutbound());
        ap.release();
        idr.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFlushH265FuParameterSetPacketsBeforeGopWhenSubscriberJoinsLate() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x2D,
                0x00, 0x00, 0x00, 0x2D,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x30,
                0x00, 0x00, 0x00, 0x30,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0xA1, 0x11
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x31,
                0x00, 0x00, 0x00, 0x30,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x21, 0x22
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x32,
                0x00, 0x00, 0x00, 0x30,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x61, 0x33
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x32,
                0x00, 0x00, 0x00, 0x32,
                0x11, 0x22, 0x33, 0x44,
                0x44, 0x01, 0x03
        }));
        stream.onInboundRtpPacket(h265IdrPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x33,
                0x00, 0x00, 0x00, 0x31,
                0x11, 0x22, 0x33, 0x44,
                0x26, 0x01, 0x55
        }));

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));
        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-h265-fu-ps",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=2", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                6,
                7,
                "RTP/AVP/TCP;unicast;interleaved=6-7"
        ));

        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        ByteBuf vps = channel.readOutbound();
        ByteBuf fuStart = channel.readOutbound();
        ByteBuf fuMid = channel.readOutbound();
        ByteBuf fuEnd = channel.readOutbound();
        ByteBuf pps = channel.readOutbound();
        ByteBuf idr = channel.readOutbound();
        assertNotNull(vps);
        assertNotNull(fuStart);
        assertNotNull(fuMid);
        assertNotNull(fuEnd);
        assertNotNull(pps);
        assertNotNull(idr);
        assertEquals(32, readH265NalType(vps));
        assertEquals(49, readH265NalType(fuStart));
        assertEquals(49, readH265NalType(fuMid));
        assertEquals(49, readH265NalType(fuEnd));
        assertEquals(34, readH265NalType(pps));
        assertEquals(19, readH265NalType(idr));
        assertNull(channel.readOutbound());
        vps.release();
        fuStart.release();
        fuMid.release();
        fuEnd.release();
        pps.release();
        idr.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldTrackRtcpSenderReportAndReceiverReportStats() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtcpPacket("trackID=0", TrackType.VIDEO, CodecType.H264, 90000, new byte[]{
                (byte) 0x80,
                (byte) 200,
                0x00, 0x06,
                0x11, 0x22, 0x33, 0x44,
                (byte) 0x83, (byte) 0xAA, 0x7E, (byte) 0x80,
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x02, 0x03, 0x04,
                0x00, 0x00, 0x00, 0x10,
                0x00, 0x00, 0x01, 0x00
        }));

        RtcpTrackStats afterSr = stream.rtcpStats("trackID=0");
        assertNotNull(afterSr);
        assertEquals(0x11223344L, afterSr.senderSsrc());
        assertEquals(0x01020304L, afterSr.senderReportRtpTimestamp());
        assertEquals(16L, afterSr.senderPacketCount());
        assertEquals(256L, afterSr.senderOctetCount());
        assertTrue(afterSr.senderReportCompactNtp() != 0L);
        assertEquals(
                Long.valueOf(afterSr.senderReportNtpMillis() + 1000L),
                afterSr.mapRtpTimestampToNtpMillis(0x01020304L + 90000L, 90000)
        );

        long compactNtp = afterSr.senderReportCompactNtp();
        long rrDelay = 65536L;

        stream.onInboundRtpPacket(rtcpPacket("trackID=0", TrackType.VIDEO, CodecType.H264, 90000, new byte[]{
                (byte) 0x81,
                (byte) 201,
                0x00, 0x07,
                0x55, 0x66, 0x77, (byte) 0x88,
                0x11, 0x22, 0x33, 0x44,
                0x05, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x64,
                0x00, 0x00, 0x00, 0x10,
                (byte) ((compactNtp >>> 24) & 0xFF),
                (byte) ((compactNtp >>> 16) & 0xFF),
                (byte) ((compactNtp >>> 8) & 0xFF),
                (byte) (compactNtp & 0xFF),
                (byte) ((rrDelay >>> 24) & 0xFF),
                (byte) ((rrDelay >>> 16) & 0xFF),
                (byte) ((rrDelay >>> 8) & 0xFF),
                (byte) (rrDelay & 0xFF)
        }));

        RtcpTrackStats stats = stream.rtcpStats("trackID=0");
        assertNotNull(stats);
        assertEquals(5, stats.fractionLost());
        assertEquals(3, stats.cumulativeLost());
        assertEquals(100L, stats.highestSequenceNumber());
        assertEquals(16L, stats.interarrivalJitter());
        assertEquals(compactNtp, stats.lastSenderReport());
        assertEquals(rrDelay, stats.delaySinceLastSenderReport());
        assertNotNull(stats.roundTripTimeMillis());
        assertTrue(stats.roundTripTimeMillis().longValue() >= 0L);
    }

    @Test
    void shouldMapAudioAndVideoTracksToSharedNtpTimeline() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtcpPacket("trackID=0", TrackType.VIDEO, CodecType.H264, 90000, new byte[]{
                (byte) 0x80, (byte) 200, 0x00, 0x06,
                0x11, 0x22, 0x33, 0x44,
                (byte) 0x83, (byte) 0xAA, 0x7E, (byte) 0x80,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x5F, (byte) 0x90,
                0x00, 0x00, 0x00, 0x10,
                0x00, 0x00, 0x01, 0x00
        }));
        stream.onInboundRtpPacket(rtcpPacket("trackID=1", TrackType.AUDIO, CodecType.AAC, 48000, new byte[]{
                (byte) 0x80, (byte) 200, 0x00, 0x06,
                0x55, 0x66, 0x77, (byte) 0x88,
                (byte) 0x83, (byte) 0xAA, 0x7E, (byte) 0x80,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, (byte) 0xBB, (byte) 0x80,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, (byte) 0x80
        }));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x00, 0x02, (byte) 0xBF, 0x20,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        }));
        stream.onInboundRtpPacket(audioPacket(new byte[]{
                (byte) 0x80, (byte) 0x61, 0x00, 0x02,
                0x00, 0x01, 0x77, 0x00,
                0x55, 0x66, 0x77, (byte) 0x88,
                0x11, 0x22
        }));

    }

    @Test
    void shouldStartGopOnlyAfterFuAKeyFrameAccessUnitCompletes() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, 0x60, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, (byte) 0x85, 0x11
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, 0x05, 0x22
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x36,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, 0x45, 0x33
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(3, snapshot.size());
        assertEquals(0x7C, snapshot.get(0).frame().payload()[12] & 0xFF);
        assertEquals(0x7C, snapshot.get(1).frame().payload()[12] & 0xFF);
        assertEquals(0x7C, snapshot.get(2).frame().payload()[12] & 0xFF);
    }

    @Test
    void shouldWaitForFuAKeyFrameCompletionBeforeStartingSubscriber() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-1",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=0", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                4,
                5,
                "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));
        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, 0x60, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, (byte) 0x85, 0x11
        }));
        assertNull(channel.readOutbound());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, 0x60, 0x12, 0x35,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, 0x05, 0x22
        }));
        assertNull(channel.readOutbound());

        stream.onInboundRtpPacket(rtpPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x36,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x7C, 0x45, 0x33
        }));

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        ByteBuf third = channel.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        first.release();
        second.release();
        third.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldStartH265GopOnlyAfterFuKeyFrameAccessUnitCompletes() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x3D,
                0x00, 0x00, 0x00, 0x3D,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x3E,
                0x00, 0x00, 0x00, 0x3E,
                0x11, 0x22, 0x33, 0x44,
                0x42, 0x01, 0x02
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x3F,
                0x00, 0x00, 0x00, 0x3F,
                0x11, 0x22, 0x33, 0x44,
                0x44, 0x01, 0x03
        }));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x40,
                0x00, 0x00, 0x00, 0x40,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0x93, 0x11
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x41,
                0x00, 0x00, 0x00, 0x40,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x13, 0x22
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x42,
                0x00, 0x00, 0x00, 0x40,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x53, 0x33
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(3, snapshot.size());
        assertEquals(49, (snapshot.get(0).frame().payload()[12] & 0x7E) >> 1);
        assertEquals(49, (snapshot.get(1).frame().payload()[12] & 0x7E) >> 1);
        assertEquals(49, (snapshot.get(2).frame().payload()[12] & 0x7E) >> 1);
    }

    @Test
    void shouldNotStartH265GopWithoutCompleteVpsSpsPps() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x60,
                0x00, 0x00, 0x00, 0x60,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x61,
                0x00, 0x00, 0x00, 0x61,
                0x11, 0x22, 0x33, 0x44,
                0x42, 0x01, 0x02
        }));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x62,
                0x00, 0x00, 0x00, 0x62,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0x93, 0x11
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x63,
                0x00, 0x00, 0x00, 0x62,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x53, 0x22
        }));

        assertTrue(stream.currentGopSnapshot().isEmpty());
    }

    @Test
    void shouldStartH265GopOnlyAfterParametersBecomeCompleteAndNextRandomAccessAuCloses() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x70,
                0x00, 0x00, 0x00, 0x70,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x71,
                0x00, 0x00, 0x00, 0x71,
                0x11, 0x22, 0x33, 0x44,
                0x42, 0x01, 0x02
        }));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x72,
                0x00, 0x00, 0x00, 0x72,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0x93, 0x11
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x73,
                0x00, 0x00, 0x00, 0x72,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x53, 0x22
        }));
        assertTrue(stream.currentGopSnapshot().isEmpty());

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x74,
                0x00, 0x00, 0x00, 0x74,
                0x11, 0x22, 0x33, 0x44,
                0x44, 0x01, 0x03
        }));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x75,
                0x00, 0x00, 0x00, 0x75,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0x93, 0x33
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x76,
                0x00, 0x00, 0x00, 0x75,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x53, 0x44
        }));

        List<InboundRtpPacket> snapshot = stream.currentGopSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals(49, (snapshot.get(0).frame().payload()[12] & 0x7E) >> 1);
        assertEquals(49, (snapshot.get(1).frame().payload()[12] & 0x7E) >> 1);
    }

    @Test
    void shouldWaitForH265FuKeyFrameCompletionBeforeStartingSubscriber() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.connect(new java.net.InetSocketAddress("127.0.0.1", 8554));

        RtspSubscriberSession subscriber = new RtspSubscriberSession(
                "subscriber-h265-fu",
                new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                channel
        );
        subscriber.transport("trackID=2", new RtspTransport(
                RtspTransportMode.RTP_TCP_INTERLEAVED,
                null,
                null,
                null,
                null,
                6,
                7,
                "RTP/AVP/TCP;unicast;interleaved=6-7"
        ));
        stream.addSubscriber(new RtspSubscriberAdapter(subscriber));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x4D,
                0x00, 0x00, 0x00, 0x4D,
                0x11, 0x22, 0x33, 0x44,
                0x40, 0x01, 0x01
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x4E,
                0x00, 0x00, 0x00, 0x4E,
                0x11, 0x22, 0x33, 0x44,
                0x42, 0x01, 0x02
        }));
        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x4F,
                0x00, 0x00, 0x00, 0x4F,
                0x11, 0x22, 0x33, 0x44,
                0x44, 0x01, 0x03
        }));

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x50,
                0x00, 0x00, 0x00, 0x50,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, (byte) 0x93, 0x11
        }));
        assertNull(channel.readOutbound());

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, 0x60, 0x00, 0x51,
                0x00, 0x00, 0x00, 0x50,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x13, 0x22
        }));
        assertNull(channel.readOutbound());

        stream.onInboundRtpPacket(h265VideoPacket(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x00, 0x52,
                0x00, 0x00, 0x00, 0x50,
                0x11, 0x22, 0x33, 0x44,
                0x62, 0x01, 0x53, 0x33
        }));

        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        ByteBuf third = channel.readOutbound();
        ByteBuf fourth = channel.readOutbound();
        ByteBuf fifth = channel.readOutbound();
        ByteBuf sixth = channel.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertNotNull(fourth);
        assertNotNull(fifth);
        assertNotNull(sixth);
        assertEquals(32, readH265NalType(first));
        assertEquals(33, readH265NalType(second));
        assertEquals(34, readH265NalType(third));
        assertEquals(49, readH265NalType(fourth));
        assertEquals(49, readH265NalType(fifth));
        assertEquals(49, readH265NalType(sixth));
        assertNull(channel.readOutbound());
        first.release();
        second.release();
        third.release();
        fourth.release();
        fifth.release();
        sixth.release();
        channel.finishAndReleaseAll();
    }

    private static InboundRtpPacket rtpPacket(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );
    }

    private static InboundRtpPacket audioPacket(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.AUDIO,
                        CodecType.AAC,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=1",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                48000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(2)
        );
    }

    private static InboundRtpPacket g711Packet(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.AUDIO,
                        CodecType.G711A,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=3",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                8000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(6)
        );
    }

    private static InboundRtpPacket mpeg4GenericPacket(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.AUDIO,
                        CodecType.MPEG4_GENERIC,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=4",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                48000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(8)
        );
    }

    private static InboundRtpPacket udpVideoPacket(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=0",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                90000,
                false,
                MediaPacketTransport.UDP,
                Integer.valueOf(20000),
                null
        );
    }

    private static InboundRtpPacket h265VideoPacket(byte[] payload) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H265,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "trackID=2",
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(4)
        );
    }

    private static InboundRtpPacket h265IdrPacket(byte[] payload) {
        return h265VideoPacket(payload);
    }

    private static InboundRtpPacket rtcpPacket(
            String trackId,
            TrackType trackType,
            CodecType codecType,
            int clockRate,
            byte[] payload
    ) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        trackType,
                        codecType,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        trackId,
                        null,
                        null,
                        false,
                        false,
                        null,
                        payload
                ),
                clockRate,
                true,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(1)
        );
    }

    private static int readNalType(ByteBuf interleavedPacket) {
        interleavedPacket.skipBytes(4 + 12);
        return interleavedPacket.readUnsignedByte();
    }

    private static int readH265NalType(ByteBuf interleavedPacket) {
        interleavedPacket.skipBytes(4 + 12);
        return (interleavedPacket.readUnsignedByte() & 0x7E) >> 1;
    }

    private static int readSequenceNumber(byte[] payload) {
        return ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
    }
}
