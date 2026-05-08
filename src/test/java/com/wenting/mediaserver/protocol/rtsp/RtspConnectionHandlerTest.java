package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.InterleavedRtpPacket;
import com.wenting.mediaserver.core.codec.rtsp.RtspRequestMessage;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
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
