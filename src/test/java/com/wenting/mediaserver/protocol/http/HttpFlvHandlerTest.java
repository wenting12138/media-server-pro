package com.wenting.mediaserver.protocol.http;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpFlvHandlerTest {

    @Test
    void shouldServeRtmpPublishedStreamOverHttpFlv() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher",
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
                "publisher",
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

        EmbeddedChannel channel = new EmbeddedChannel(new HttpFlvHandler(registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/live/cam01.flv"));

        DefaultHttpResponse response = channel.readOutbound();
        DefaultHttpContent flvHeader = channel.readOutbound();
        DefaultHttpContent configTag = channel.readOutbound();
        DefaultHttpContent keyFrameTag = channel.readOutbound();

        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertEquals("video/x-flv", response.headers().get("Content-Type"));
        assertFlvHeader(flvHeader.content());
        assertEquals(0x09, configTag.content().getUnsignedByte(0));
        assertEquals(0x09, keyFrameTag.content().getUnsignedByte(0));

        flvHeader.release();
        configTag.release();
        keyFrameTag.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeRtspPublishedStreamOverHttpFlv() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam02");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new HttpFlvHandler(registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/live/cam02.flv"));

        DefaultHttpResponse response = channel.readOutbound();
        DefaultHttpContent flvHeader = channel.readOutbound();
        DefaultHttpContent configTag = channel.readOutbound();
        DefaultHttpContent keyFrameTag = channel.readOutbound();

        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertFlvHeader(flvHeader.content());
        assertEquals(0x09, configTag.content().getUnsignedByte(0));
        assertEquals(0x09, keyFrameTag.content().getUnsignedByte(0));

        flvHeader.release();
        configTag.release();
        keyFrameTag.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeRtmpPublishedH264AndAacStreamOverHttpFlv() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam03");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(rtmpFrame(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                0L,
                true,
                true,
                new byte[]{
                        0x01, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE1,
                        0x00, 0x08, 0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78,
                        0x01, 0x00, 0x04, 0x68, (byte) 0xEE, 0x3C, (byte) 0x80
                }
        ));
        stream.onInboundFrame(rtmpFrame(
                streamKey,
                "video-h264",
                TrackType.VIDEO,
                CodecType.H264,
                100L,
                true,
                false,
                new byte[]{
                        0x00, 0x00, 0x00, 0x03,
                        0x65, 0x11, 0x22
                }
        ));
        stream.onInboundFrame(rtmpFrame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                0L,
                false,
                true,
                new byte[]{0x11, (byte) 0x90}
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new HttpFlvHandler(registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/live/cam03.flv"));

        DefaultHttpResponse response = channel.readOutbound();
        DefaultHttpContent flvHeader = channel.readOutbound();
        DefaultHttpContent videoConfigTag = channel.readOutbound();
        DefaultHttpContent videoKeyTag = channel.readOutbound();
        DefaultHttpContent audioConfigTag = channel.readOutbound();

        stream.onInboundFrame(rtmpFrame(
                streamKey,
                "audio-aac",
                TrackType.AUDIO,
                CodecType.AAC,
                20L,
                false,
                false,
                new byte[]{0x01, 0x02, 0x03}
        ));
        DefaultHttpContent audioDataTag = channel.readOutbound();

        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertFlvHeader(flvHeader.content());
        assertVideoTag(videoConfigTag.content(), 7, 1, 0, 0L);
        assertVideoTag(videoKeyTag.content(), 7, 1, 1, 100L);
        assertAudioTag(audioConfigTag.content(), 10, 0, 0L);
        assertAudioTag(audioDataTag.content(), 10, 1, 20L);

        flvHeader.release();
        videoConfigTag.release();
        videoKeyTag.release();
        audioConfigTag.release();
        audioDataTag.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeRtspPublishedH264AndAacStreamOverHttpFlv() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam04");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        registerRtspPublisherSession(
                registry,
                streamKey,
                "v=0\r\n"
                        + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                        + "s=cam04\r\n"
                        + "c=IN IP4 0.0.0.0\r\n"
                        + "t=0 0\r\n"
                        + "m=video 0 RTP/AVP 96\r\n"
                        + "a=rtpmap:96 H264/90000\r\n"
                        + "a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z2QAH6zZQHg=,aO48gA==\r\n"
                        + "a=control:video-h264\r\n"
                        + "m=audio 0 RTP/AVP 97\r\n"
                        + "a=rtpmap:97 MPEG4-GENERIC/48000/2\r\n"
                        + "a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; config=1190\r\n"
                        + "a=control:audio-aac\r\n"
        );
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                90000,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "audio-aac",
                CodecType.MPEG4_GENERIC,
                TrackType.AUDIO,
                48000,
                4,
                1024L,
                true,
                new byte[]{0x00, 0x10, 0x00, 0x10, 0x11, 0x22}
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new HttpFlvHandler(registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/live/cam04.flv"));

        DefaultHttpResponse response = channel.readOutbound();
        DefaultHttpContent flvHeader = channel.readOutbound();
        DefaultHttpContent videoConfigTag = channel.readOutbound();
        DefaultHttpContent videoKeyTag = channel.readOutbound();
        DefaultHttpContent audioConfigTag = channel.readOutbound();
        DefaultHttpContent audioDataTag = channel.readOutbound();

        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertFlvHeader(flvHeader.content());
        assertVideoTag(videoConfigTag.content(), 7, 1, 0, 0L);
        assertVideoTag(videoKeyTag.content(), 7, 1, 1, 0L);
        assertAudioTag(audioConfigTag.content(), 10, 0, 0L);
        assertAudioTag(audioDataTag.content(), 10, 1, 0L);

        flvHeader.release();
        videoConfigTag.release();
        videoKeyTag.release();
        audioConfigTag.release();
        audioDataTag.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeRtspPublishedH265AndG711StreamOverHttpFlv() {
        StreamRegistry registry = new StreamRegistry();
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam05");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                90000,
                1,
                1000L,
                false,
                new byte[]{0x40, 0x01, 0x0C, 0x01}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                90000,
                2,
                1000L,
                false,
                new byte[]{0x42, 0x01, 0x01, 0x60}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                90000,
                3,
                1000L,
                false,
                new byte[]{0x44, 0x01, (byte) 0xC0, (byte) 0xF1}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                90000,
                4,
                1000L,
                true,
                new byte[]{0x26, 0x01, 0x55, 0x66}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                StreamProtocol.RTSP,
                streamKey,
                "audio-g711u",
                CodecType.G711U,
                TrackType.AUDIO,
                8000,
                5,
                160L,
                true,
                new byte[]{0x11, 0x22, 0x33}
        ));

        EmbeddedChannel channel = new EmbeddedChannel(new HttpFlvHandler(registry));
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/live/cam05.flv"));

        DefaultHttpResponse response = channel.readOutbound();
        DefaultHttpContent flvHeader = channel.readOutbound();
        DefaultHttpContent videoConfigTag = channel.readOutbound();
        DefaultHttpContent videoKeyTag = channel.readOutbound();
        DefaultHttpContent audioDataTag = channel.readOutbound();

        assertNotNull(response);
        assertEquals(200, response.status().code());
        assertFlvHeader(flvHeader.content());
        assertVideoTag(videoConfigTag.content(), 12, 1, 0, 0L);
        assertVideoTag(videoKeyTag.content(), 12, 1, 1, 0L);
        assertAudioTag(audioDataTag.content(), 8, null, 0L);

        flvHeader.release();
        videoConfigTag.release();
        videoKeyTag.release();
        audioDataTag.release();
        channel.finishAndReleaseAll();
    }

    private static void assertFlvHeader(ByteBuf buf) {
        assertEquals('F', buf.getUnsignedByte(0));
        assertEquals('L', buf.getUnsignedByte(1));
        assertEquals('V', buf.getUnsignedByte(2));
        assertEquals(0x01, buf.getUnsignedByte(3));
        assertEquals(0x05, buf.getUnsignedByte(4));
    }

    private static void assertVideoTag(ByteBuf buf, int codecId, int frameType, int packetType, long timestamp) {
        assertEquals(0x09, buf.getUnsignedByte(0));
        assertEquals(codecId, buf.getUnsignedByte(11) & 0x0F);
        assertEquals(frameType, (buf.getUnsignedByte(11) >> 4) & 0x0F);
        assertEquals(packetType, buf.getUnsignedByte(12));
        assertEquals(timestamp, readFlvTimestamp(buf));
    }

    private static void assertAudioTag(ByteBuf buf, int soundFormat, Integer aacPacketType, long timestamp) {
        assertEquals(0x08, buf.getUnsignedByte(0));
        assertEquals(soundFormat, (buf.getUnsignedByte(11) >> 4) & 0x0F);
        if (aacPacketType != null) {
            assertEquals(aacPacketType.intValue(), buf.getUnsignedByte(12));
        }
        assertEquals(timestamp, readFlvTimestamp(buf));
    }

    private static long readFlvTimestamp(ByteBuf buf) {
        return ((long) buf.getUnsignedByte(7) << 24)
                | ((long) buf.getUnsignedByte(4) << 16)
                | ((long) buf.getUnsignedByte(5) << 8)
                | buf.getUnsignedByte(6);
    }

    private static InboundMediaFrame rtmpFrame(
            StreamKey streamKey,
            String trackId,
            TrackType trackType,
            CodecType codecType,
            Long timestampMillis,
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
                timestampMillis,
                timestampMillis,
                keyFrame,
                configFrame,
                null,
                payload
        );
    }

    private static void registerRtspPublisherSession(StreamRegistry registry, StreamKey streamKey, String sdp) {
        RtspSession session = new RtspSession("publisher-" + streamKey.stream());
        session.streamKey(streamKey);
        session.role(com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole.PUBLISHER);
        session.sdpOrigin(sdp);
        registry.getRtspSessionManager().register(session);
    }

    private static InboundRtpPacket rtpPacket(
            StreamProtocol sourceProtocol,
            StreamKey streamKey,
            String trackId,
            CodecType codecType,
            TrackType trackType,
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
                        sourceProtocol,
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
