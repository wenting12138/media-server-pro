package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.InterleavedRtpPacket;
import com.wenting.mediaserver.core.codec.rtsp.RtspRequestMessage;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.stats.InMemoryTrafficStatsService;
import com.wenting.mediaserver.core.enums.traffic.TrafficProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspConnectionHandlerTest {

    @Test
    void shouldTransitionPublisherFlowFromAnnounceToRecord() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(new StreamRegistry()));

        channel.writeInbound(request(
                "ANNOUNCE",
                "rtsp://example/live/stream",
                "v=0",
                "Content-Length", "3"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=0",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=0-1"
        ));
        FullHttpResponse setupResponse = channel.readOutbound();
        assertResponse(setupResponse, RtspResponseStatuses.OK, "1", true);
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", setupResponse.headers().get(RtspHeaderNames.TRANSPORT));
        setupResponse.release();

        channel.writeInbound(request("RECORD", "rtsp://example/live/stream", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{0x01, 0x02});
        assertFalse(channel.writeInbound(new InterleavedRtpPacket(0, payload.retain())));
        payload.release();
        assertNull(channel.readOutbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldRejectRecordBeforeSetup() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(new StreamRegistry()));

        channel.writeInbound(request(
                "ANNOUNCE",
                "rtsp://example/live/stream",
                "v=0",
                "Content-Length", "3"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("RECORD", "rtsp://example/live/stream", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.METHOD_NOT_VALID, "1", true).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldTransitionSubscriberFlowToPlaying() {
        String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=1\r\n";
        RtspSessionManager sessionManager = new RtspSessionManager();
        StreamRegistry registry = new StreamRegistry(sessionManager);
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "app", "stream");
        registry.registerPublishedStream(
                streamKey,
                new DefaultPublishedStream(streamKey)
        );
        RtspSession publisherSession = sessionManager.register(new RtspSession("publisher-session"));
        publisherSession.role(com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole.PUBLISHER);
        publisherSession.streamKey(streamKey);
        publisherSession.sdpOrigin(sdp);
        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(registry));

        channel.writeInbound(request("DESCRIBE", "rtsp://example/app/stream", ""));
        FullHttpResponse describeResponse = channel.readOutbound();
        assertResponse(describeResponse, RtspResponseStatuses.OK, "1", false);
        assertEquals("application/sdp", describeResponse.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("rtsp://example/app/stream", describeResponse.headers().get(RtspHeaderNames.CONTENT_BASE));
        assertEquals(sdp, describeResponse.content().toString(CharsetUtil.UTF_8));
        describeResponse.release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/app/stream/trackID=1",
                "",
                "Transport", "RTP/AVP;unicast;client_port=5000-5001"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("PLAY", "rtsp://example/app/stream", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldDescribeAndPlayRtmpPublishedH264StreamOverRtsp() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "rtmp-publisher",
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
        ));
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "rtmp-publisher",
                streamKey,
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

        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(registry));

        channel.writeInbound(request("DESCRIBE", "rtsp://example/live/cam01", ""));
        FullHttpResponse describeResponse = channel.readOutbound();
        assertResponse(describeResponse, RtspResponseStatuses.OK, "1", false);
        String sdp = describeResponse.content().toString(CharsetUtil.UTF_8);
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(sdp.contains("a=control:video-h264"));
        assertTrue(sdp.contains("sprop-parameter-sets="));
        describeResponse.release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/cam01/video-h264",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("PLAY", "rtsp://example/live/cam01", ""));

        Object first = channel.readOutbound();
        Object second = channel.readOutbound();
        Object third = channel.readOutbound();
        Object fourth = channel.readOutbound();
        assertTrue(first instanceof ByteBuf);
        assertTrue(second instanceof ByteBuf);
        assertTrue(third instanceof ByteBuf);
        assertTrue(fourth instanceof FullHttpResponse);
        ByteBuf sps = (ByteBuf) first;
        ByteBuf pps = (ByteBuf) second;
        ByteBuf idr = (ByteBuf) third;
        assertEquals(4, sps.getUnsignedByte(1));
        assertEquals(4, pps.getUnsignedByte(1));
        assertEquals(4, idr.getUnsignedByte(1));
        assertEquals(0x67, sps.getUnsignedByte(4 + 12));
        assertEquals(0x68, pps.getUnsignedByte(4 + 12));
        assertEquals(0x65, idr.getUnsignedByte(4 + 12));
        sps.release();
        pps.release();
        idr.release();
        assertResponse(fourth, RtspResponseStatuses.OK, "1", true).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldDescribeAndPlayRtmpPublishedAacStreamOverRtsp() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "aac01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                CodecType.AAC,
                "rtmp-publisher",
                streamKey,
                "audio-aac",
                Long.valueOf(0L),
                Long.valueOf(0L),
                false,
                true,
                null,
                new byte[]{0x11, (byte) 0x90}
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(registry));

        channel.writeInbound(request("DESCRIBE", "rtsp://example/live/aac01", ""));
        FullHttpResponse describeResponse = channel.readOutbound();
        assertResponse(describeResponse, RtspResponseStatuses.OK, "1", false);
        String sdp = describeResponse.content().toString(CharsetUtil.UTF_8);
        assertTrue(sdp.contains("a=rtpmap:97 MPEG4-GENERIC/48000/2"));
        assertTrue(sdp.contains("a=control:audio-aac"));
        assertTrue(sdp.contains("config=1190"));
        describeResponse.release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/aac01/audio-aac",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=6-7"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("PLAY", "rtsp://example/live/aac01", ""));
        Object first = channel.readOutbound();
        assertResponse(first, RtspResponseStatuses.OK, "1", true).release();

        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                CodecType.AAC,
                "rtmp-publisher",
                streamKey,
                "audio-aac",
                Long.valueOf(100L),
                Long.valueOf(100L),
                false,
                false,
                null,
                new byte[]{0x55, 0x66}
        ));

        ByteBuf audio = channel.readOutbound();
        assertNotNull(audio);
        assertEquals('$', audio.readByte());
        assertEquals(6, audio.readUnsignedByte());
        assertEquals(18, audio.readUnsignedShort());
        assertEquals(0x80, audio.readUnsignedByte());
        assertEquals(0xE1, audio.readUnsignedByte());
        audio.skipBytes(10);
        assertEquals(0x00, audio.readUnsignedByte());
        assertEquals(0x10, audio.readUnsignedByte());
        assertEquals(0x00, audio.readUnsignedByte());
        assertEquals(0x10, audio.readUnsignedByte());
        assertEquals(0x55, audio.readUnsignedByte());
        assertEquals(0x66, audio.readUnsignedByte());
        audio.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldDescribeAndPlayRtmpPublishedH265StreamOverRtsp() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "hevc01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H265,
                "rtmp-publisher",
                streamKey,
                "video-h265",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{
                        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x03,
                        0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0C,
                        0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x01,
                        0x22, 0x00, 0x01, 0x00, 0x03, 0x44, 0x01, (byte) 0xC0
                }
        ));
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H265,
                "rtmp-publisher",
                streamKey,
                "video-h265",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                new byte[]{
                        0x00, 0x00, 0x00, 0x04,
                        0x26, 0x01, 0x11, 0x22
                }
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(registry));

        channel.writeInbound(request("DESCRIBE", "rtsp://example/live/hevc01", ""));
        FullHttpResponse describeResponse = channel.readOutbound();
        assertResponse(describeResponse, RtspResponseStatuses.OK, "1", false);
        String sdp = describeResponse.content().toString(CharsetUtil.UTF_8);
        assertTrue(sdp.contains("a=rtpmap:98 H265/90000"));
        assertTrue(sdp.contains("a=control:video-h265"));
        assertTrue(sdp.contains("sprop-vps="));
        assertTrue(sdp.contains("sprop-sps="));
        assertTrue(sdp.contains("sprop-pps="));
        describeResponse.release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/hevc01/video-h265",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=8-9"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("PLAY", "rtsp://example/live/hevc01", ""));

        Object first = channel.readOutbound();
        Object second = channel.readOutbound();
        Object third = channel.readOutbound();
        Object fourth = channel.readOutbound();
        Object fifth = channel.readOutbound();
        assertTrue(first instanceof ByteBuf);
        assertTrue(second instanceof ByteBuf);
        assertTrue(third instanceof ByteBuf);
        assertTrue(fourth instanceof ByteBuf);
        assertTrue(fifth instanceof FullHttpResponse);
        ByteBuf vps = (ByteBuf) first;
        ByteBuf sps = (ByteBuf) second;
        ByteBuf pps = (ByteBuf) third;
        ByteBuf idr = (ByteBuf) fourth;
        assertEquals(8, vps.getUnsignedByte(1));
        assertEquals(8, sps.getUnsignedByte(1));
        assertEquals(8, pps.getUnsignedByte(1));
        assertEquals(8, idr.getUnsignedByte(1));
        assertEquals(32, (vps.getUnsignedByte(4 + 12) & 0x7E) >> 1);
        assertEquals(33, (sps.getUnsignedByte(4 + 12) & 0x7E) >> 1);
        assertEquals(34, (pps.getUnsignedByte(4 + 12) & 0x7E) >> 1);
        assertEquals(19, (idr.getUnsignedByte(4 + 12) & 0x7E) >> 1);
        vps.release();
        sps.release();
        pps.release();
        idr.release();
        assertResponse(fifth, RtspResponseStatuses.OK, "1", true).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldFanoutInterleavedPacketToSubscriberAfterPlay() {
        StreamRegistry registry = new StreamRegistry();
        EmbeddedChannel publisherChannel = new EmbeddedChannel(new RtspConnectionHandler(registry));
        EmbeddedChannel subscriberChannel = new EmbeddedChannel(new RtspConnectionHandler(registry));

        String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=0\r\n";
        publisherChannel.writeInbound(request(
                "ANNOUNCE",
                "rtsp://example/live/stream",
                sdp,
                "Content-Length", String.valueOf(sdp.length())
        ));
        assertResponse(publisherChannel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        publisherChannel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=0",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=0-1"
        ));
        assertResponse(publisherChannel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        publisherChannel.writeInbound(request("RECORD", "rtsp://example/live/stream", ""));
        assertResponse(publisherChannel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        subscriberChannel.writeInbound(request("DESCRIBE", "rtsp://example/live/stream", ""));
        FullHttpResponse describeResponse = assertResponse(subscriberChannel.readOutbound(), RtspResponseStatuses.OK, "1", false);
        assertEquals(sdp, describeResponse.content().toString(CharsetUtil.UTF_8));
        describeResponse.release();

        subscriberChannel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=0",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=4-5"
        ));
        assertResponse(subscriberChannel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        subscriberChannel.writeInbound(request("PLAY", "rtsp://example/live/stream", ""));
        assertResponse(subscriberChannel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{
                (byte) 0x80, (byte) 0xE0, 0x12, 0x34,
                0x01, 0x02, 0x03, 0x04,
                0x11, 0x22, 0x33, 0x44,
                0x65, 0x11, 0x22
        });
        assertFalse(publisherChannel.writeInbound(new InterleavedRtpPacket(0, payload.retain())));
        payload.release();

        ByteBuf outbound = subscriberChannel.readOutbound();
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

        assertFalse(publisherChannel.finishAndReleaseAll());
        assertFalse(subscriberChannel.finishAndReleaseAll());
    }

    @Test
    void shouldReturnNotFoundWhenDescribeTargetsMissingPublisher() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspConnectionHandler(new StreamRegistry()));

        channel.writeInbound(request("DESCRIBE", "rtsp://example/app/missing", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.NOT_FOUND, "1", false).release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldReturnServerPortsForUdpSetupWhenAllocatorIsInjected() {
        RtspSessionManager sessionManager = new RtspSessionManager();
        StreamRegistry registry = new StreamRegistry(sessionManager);
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "app", "stream");
        String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=1\r\n";
        registry.registerPublishedStream(
                streamKey,
                new DefaultPublishedStream(streamKey)
        );
        RtspSession publisherSession = sessionManager.register(new RtspSession("publisher-session"));
        publisherSession.role(com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole.PUBLISHER);
        publisherSession.streamKey(streamKey);
        publisherSession.sdpOrigin(sdp);
        EmbeddedChannel channel = new EmbeddedChannel(
                new RtspConnectionHandler(registry, new RtpPortAllocator(20000, 20003))
        );

        channel.writeInbound(request("DESCRIBE", "rtsp://example/app/stream", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", false).release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/app/stream/trackID=1",
                "",
                "Transport", "RTP/AVP;unicast;client_port=5000-5001"
        ));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(
                "RTP/AVP;unicast;client_port=5000-5001;server_port=20000-20001",
                response.headers().get(RtspHeaderNames.TRANSPORT)
        );
        assertResponse(response, RtspResponseStatuses.OK, "1", true);
        response.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldAllocateDifferentUdpServerPortsForDifferentTracksInSameSession() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new RtspConnectionHandler(new StreamRegistry(), new RtpPortAllocator(20000, 20005))
        );

        channel.writeInbound(request("ANNOUNCE", "rtsp://example/live/stream", "v=0", "Content-Length", "3"));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=0",
                "",
                "Transport", "RTP/AVP;unicast;client_port=5000-5001"
        ));
        FullHttpResponse first = channel.readOutbound();
        assertEquals(
                "RTP/AVP;unicast;client_port=5000-5001;server_port=20000-20001",
                first.headers().get(RtspHeaderNames.TRANSPORT)
        );
        assertResponse(first, RtspResponseStatuses.OK, "1", true);
        first.release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=1",
                "",
                "Transport", "RTP/AVP;unicast;client_port=5002-5003"
        ));
        FullHttpResponse second = channel.readOutbound();
        assertEquals(
                "RTP/AVP;unicast;client_port=5002-5003;server_port=20002-20003",
                second.headers().get(RtspHeaderNames.TRANSPORT)
        );
        assertResponse(second, RtspResponseStatuses.OK, "1", true);
        second.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldRecordRtspControlAndInterleavedTrafficStats() {
        InMemoryTrafficStatsService trafficStatsService = new InMemoryTrafficStatsService();
        EmbeddedChannel channel = new EmbeddedChannel(
                new RtspConnectionHandler(new StreamRegistry(), null, null, trafficStatsService)
        );

        channel.writeInbound(request("ANNOUNCE", "rtsp://example/live/stream", "v=0", "Content-Length", "3"));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request(
                "SETUP",
                "rtsp://example/live/stream/trackID=0",
                "",
                "Transport", "RTP/AVP/TCP;unicast;interleaved=0-1"
        ));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        channel.writeInbound(request("RECORD", "rtsp://example/live/stream", ""));
        assertResponse(channel.readOutbound(), RtspResponseStatuses.OK, "1", true).release();

        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{0x11, 0x22, 0x33, 0x44});
        assertFalse(channel.writeInbound(new InterleavedRtpPacket(0, payload.retain())));
        payload.release();

        assertTrue(trafficStatsService.protocolSnapshot(TrafficProtocol.RTSP_CONTROL).packets() > 0);
        assertEquals(1, trafficStatsService.protocolSnapshot(TrafficProtocol.RTP_TCP_INTERLEAVED).packets());
        assertEquals(4, trafficStatsService.protocolSnapshot(TrafficProtocol.RTP_TCP_INTERLEAVED).bytes());
        assertFalse(channel.finishAndReleaseAll());
    }

    private static RtspRequestMessage request(String method, String uri, String body, String... headerPairs) {
        ByteBuf content = body == null || body.isEmpty()
                ? Unpooled.EMPTY_BUFFER
                : Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("cseq", "1");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i].toLowerCase(java.util.Locale.ROOT), headerPairs[i + 1]);
        }
        return new RtspRequestMessage(method, uri, "RTSP/1.0", Collections.unmodifiableMap(headers), content);
    }

    private static FullHttpResponse assertResponse(Object outbound, io.netty.handler.codec.http.HttpResponseStatus status,
                                                   String cSeq, boolean expectSession) {
        FullHttpResponse response = (FullHttpResponse) outbound;
        assertNotNull(response);
        assertEquals(status, response.status());
        assertEquals(cSeq, response.headers().get(RtspHeaderNames.CSEQ));
        if (expectSession) {
            assertNotNull(response.headers().get(RtspHeaderNames.SESSION));
        } else {
            assertNull(response.headers().get(RtspHeaderNames.SESSION));
        }
        return response;
    }
}
