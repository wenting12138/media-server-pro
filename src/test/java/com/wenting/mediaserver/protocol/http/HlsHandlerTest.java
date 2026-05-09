package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsHandlerTest {

    @Test
    void shouldServePlaylistAndSegmentForRtmpPublishedStream() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(frame(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                0L,
                0L,
                true,
                true,
                new byte[] {
                        0x01, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE1,
                        0x00, 0x08, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78,
                        0x01, 0x00, 0x04, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
                }
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                0L,
                0L,
                false,
                true,
                new byte[] {0x11, (byte) 0x90}
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                100L,
                100L,
                true,
                false,
                new byte[] {0x00, 0x00, 0x00, 0x03, 0x65, 0x11, 0x22}
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                100L,
                100L,
                false,
                false,
                new byte[] {0x01, 0x02, 0x03}
        ));
        HlsSessionManager sessionManager = new HlsSessionManager(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new HlsHandler(registry, sessionManager));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/cam01/index.m3u8"));

        FullHttpResponse playlistResponse = channel.readOutbound();
        assertNotNull(playlistResponse);
        assertEquals(HttpResponseStatus.OK, playlistResponse.status());
        assertEquals("application/vnd.apple.mpegurl", playlistResponse.headers().get("Content-Type"));
        String body = playlistResponse.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("#EXTM3U"));
        assertTrue(body.contains("seg-00000.ts"));
        playlistResponse.release();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/cam01/seg-00000.ts"));
        FullHttpResponse segmentResponse = channel.readOutbound();
        assertNotNull(segmentResponse);
        assertEquals(HttpResponseStatus.OK, segmentResponse.status());
        assertEquals("video/mp2t", segmentResponse.headers().get("Content-Type"));
        assertEquals(0x47, segmentResponse.content().getUnsignedByte(0));
        segmentResponse.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldReturn404ForMissingHlsStream() {
        StreamRegistry registry = new StreamRegistry();
        HlsSessionManager sessionManager = new HlsSessionManager(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new HlsHandler(registry, sessionManager));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/missing/index.m3u8"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        response.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldServePlaylistAndSegmentForRtspPublishedStream() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam02");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                90000,
                1,
                1000L,
                false,
                new byte[] {0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                90000,
                2,
                1000L,
                false,
                new byte[] {0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                90000,
                3,
                1000L,
                true,
                new byte[] {0x65, 0x11, 0x22}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.MPEG4_GENERIC,
                48000,
                4,
                1024L,
                true,
                new byte[] {0x00, 0x10, 0x00, 0x10, 0x11, 0x22}
        ));

        HlsSessionManager sessionManager = new HlsSessionManager(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new HlsHandler(registry, sessionManager));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/cam02/index.m3u8"));
        FullHttpResponse playlistResponse = channel.readOutbound();
        assertNotNull(playlistResponse);
        assertEquals(HttpResponseStatus.OK, playlistResponse.status());
        String body = playlistResponse.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("#EXTM3U"));
        assertTrue(body.contains("seg-00000.ts"));
        playlistResponse.release();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/cam02/seg-00000.ts"));
        FullHttpResponse segmentResponse = channel.readOutbound();
        assertNotNull(segmentResponse);
        assertEquals(HttpResponseStatus.OK, segmentResponse.status());
        assertEquals(0x47, segmentResponse.content().getUnsignedByte(0));
        segmentResponse.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldServePlaylistAndSegmentForRtmpPublishedHevcStream() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "hevc01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(frame(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                0L,
                0L,
                true,
                true,
                new byte[] {
                        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0, 0x00,
                        (byte) 0xFC, (byte) 0xFD, 0x03,
                        0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0x01, 0x0C,
                        0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x01,
                        0x22, 0x00, 0x01, 0x00, 0x03, 0x44, 0x01, (byte) 0xC0
                }
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                0L,
                0L,
                false,
                true,
                new byte[] {0x11, (byte) 0x90}
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                100L,
                100L,
                true,
                false,
                new byte[] {0x00, 0x00, 0x00, 0x04, 0x26, 0x01, 0x55, 0x66}
        ));
        stream.onInboundFrame(frame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                100L,
                100L,
                false,
                false,
                new byte[] {0x01, 0x02, 0x03}
        ));
        HlsSessionManager sessionManager = new HlsSessionManager(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new HlsHandler(registry, sessionManager));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/hevc01/index.m3u8"));
        FullHttpResponse playlistResponse = channel.readOutbound();
        assertNotNull(playlistResponse);
        assertEquals(HttpResponseStatus.OK, playlistResponse.status());
        String body = playlistResponse.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("seg-00000.ts"));
        playlistResponse.release();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/hevc01/seg-00000.ts"));
        FullHttpResponse segmentResponse = channel.readOutbound();
        assertNotNull(segmentResponse);
        assertEquals(HttpResponseStatus.OK, segmentResponse.status());
        assertEquals(0x47, segmentResponse.content().getUnsignedByte(0));
        segmentResponse.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    @Test
    void shouldServePlaylistAndSegmentForRtspPublishedHevcStream() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "hevc02");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                90000,
                1,
                2000L,
                false,
                new byte[] {0x40, 0x01, 0x0C}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                90000,
                2,
                2000L,
                false,
                new byte[] {0x42, 0x01, 0x01}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                90000,
                3,
                2000L,
                false,
                new byte[] {0x44, 0x01, (byte) 0xC0}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                TrackType.VIDEO,
                CodecType.H265,
                90000,
                4,
                2000L,
                true,
                new byte[] {0x26, 0x01, 0x55, 0x66}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.MPEG4_GENERIC,
                48000,
                5,
                1024L,
                true,
                new byte[] {0x00, 0x10, 0x00, 0x10, 0x11, 0x22}
        ));

        HlsSessionManager sessionManager = new HlsSessionManager(registry);
        EmbeddedChannel channel = new EmbeddedChannel(new HlsHandler(registry, sessionManager));

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/hevc02/index.m3u8"));
        FullHttpResponse playlistResponse = channel.readOutbound();
        assertNotNull(playlistResponse);
        assertEquals(HttpResponseStatus.OK, playlistResponse.status());
        String body = playlistResponse.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("seg-00000.ts"));
        playlistResponse.release();

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hls/live/hevc02/seg-00000.ts"));
        FullHttpResponse segmentResponse = channel.readOutbound();
        assertNotNull(segmentResponse);
        assertEquals(HttpResponseStatus.OK, segmentResponse.status());
        assertEquals(0x47, segmentResponse.content().getUnsignedByte(0));
        segmentResponse.release();
        channel.finishAndReleaseAll();
        sessionManager.close();
    }

    private static InboundMediaFrame frame(
            StreamKey streamKey,
            String trackId,
            TrackType trackType,
            CodecType codecType,
            Long ptsMillis,
            Long dtsMillis,
            boolean keyFrame,
            boolean configFrame,
            byte[] payload
    ) {
        return new InboundMediaFrame(
                StreamProtocol.RTMP,
                trackType,
                codecType,
                "publisher",
                streamKey,
                trackId,
                ptsMillis,
                dtsMillis,
                keyFrame,
                configFrame,
                null,
                payload
        );
    }

    private static InboundRtpPacket rtpPacket(
            StreamKey streamKey,
            String trackId,
            TrackType trackType,
            CodecType codecType,
            int clockRate,
            int sequenceNumber,
            long timestamp,
            boolean marker,
            byte[] rtpPayload
    ) {
        byte[] packet = new byte[12 + rtpPayload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (marker ? 0xE0 : 0x60);
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = 0x11;
        packet[9] = 0x22;
        packet[10] = 0x33;
        packet[11] = 0x44;
        System.arraycopy(rtpPayload, 0, packet, 12, rtpPayload.length);
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        trackType,
                        codecType,
                        "publisher",
                        streamKey,
                        trackId,
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                clockRate,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );
    }
}
