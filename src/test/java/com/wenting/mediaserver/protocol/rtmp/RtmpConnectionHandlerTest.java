package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.transcode.orchestrator.StreamTransformOrchestrator;
import com.wenting.mediaserver.core.transcode.orchestrator.WebRtcPlaybackStreamTransformOrchestrator;
import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpCommandMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpDataMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import com.wenting.mediaserver.protocol.rtsp.RtspSession;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtmpConnectionHandlerTest {

    @Test
    void shouldSendUpstreamOnFiToRtmpPublisherWhenDerivedPlaybackRequestsKeyframe() {
        RtspSessionManager rtspSessionManager = new RtspSessionManager();
        RtmpSessionManager rtmpSessionManager = new RtmpSessionManager();
        StreamRegistry registry = new StreamRegistry(rtspSessionManager, rtmpSessionManager);
        StreamTransformOrchestrator orchestrator = new WebRtcPlaybackStreamTransformOrchestrator(
                registry,
                registry.webRtcPlaybackSuffix()
        );
        registry.setStreamTransformOrchestrator(orchestrator);
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, rtmpSessionManager));
        try {
            Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
            connectObject.put("app", "live");
            channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
            drainOutbound(channel, 4);
            channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
            drainOutbound(channel, 1);
            channel.writeInbound(new RtmpCommandMessage(5, 0L, 1, "publish", 0.0d, null, Collections.<Object>singletonList("camKey")));
            channel.readOutbound();

            DefaultPublishedStream derived = (DefaultPublishedStream) registry.findPublishedStreamByPath("live", "camKey__webrtc");
            assertNotNull(derived);
            assertTrue(derived.requestKeyFrame("video-h264"));

            RtmpDataMessage onFi = channel.readOutbound();
            assertNotNull(onFi);
            assertEquals(5, onFi.chunkStreamId());
            assertEquals(1, onFi.messageStreamId());
            assertEquals(Collections.<Object>singletonList("onFI"), onFi.values());
        } finally {
            orchestrator.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldTrackInboundBytesPerSession() {
        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(new StreamRegistry(), sessionManager));

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, null, Collections.<Object>emptyList()));

        assertEquals(1, sessionManager.count());
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertTrue(session.inboundBytes() > 0L);

        channel.close();
        assertEquals(0, sessionManager.count());
    }

    @Test
    void shouldRegisterPublishedStreamAndAcceptAudioVideoAfterPublish() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        channel.readOutbound();
        channel.readOutbound();
        channel.readOutbound();
        channel.readOutbound();

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        channel.readOutbound();

        channel.writeInbound(new RtmpCommandMessage(5, 0L, 1, "publish", 0.0d, null, Collections.<Object>singletonList("cam01")));
        channel.readOutbound();

        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals(RtmpSessionRole.PUBLISHER, session.role());

        channel.writeInbound(new RtmpVideoMessage(6, 100L, 1, new byte[]{0x17, 0x00, 0x00, 0x00, 0x00, 0x01}));
        channel.writeInbound(new RtmpAudioMessage(4, 120L, 1, new byte[]{(byte) 0xAF, 0x00, 0x12, 0x10}));

        IPublishedStream stream = registry.findPublishedStream(new StreamKey(StreamProtocol.RTMP, "live", "cam01"));
        assertNotNull(stream);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldAttachPlaySubscriberAndFlushCachedRtmpFrames() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam01");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-1",
                streamKey,
                "video-h264",
                Long.valueOf(0L),
                Long.valueOf(0L),
                true,
                true,
                null,
                new byte[]{0x01, 0x64}
        ));
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-1",
                streamKey,
                "video-h264",
                Long.valueOf(100L),
                Long.valueOf(100L),
                true,
                false,
                null,
                new byte[]{0x11, 0x22, 0x33}
        ));
        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.AUDIO,
                CodecType.AAC,
                "publisher-1",
                streamKey,
                "audio-aac",
                Long.valueOf(90L),
                Long.valueOf(90L),
                false,
                true,
                null,
                new byte[]{0x12, 0x10}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);

        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam01")));

        RtmpCommandMessage playStatus = channel.readOutbound();
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals(RtmpSessionRole.SUBSCRIBER, session.role());
        RtmpVideoMessage config = channel.readOutbound();
        RtmpVideoMessage keyFrame = channel.readOutbound();
        RtmpAudioMessage audioConfig = channel.readOutbound();
        assertNotNull(playStatus);
        assertEquals("onStatus", playStatus.commandName());
        assertEquals("NetStream.Play.Start", ((Map<?, ?>) playStatus.arguments().get(0)).get("code"));
        assertNotNull(config);
        assertEquals(0, config.avcPacketType().intValue());
        assertNotNull(keyFrame);
        assertEquals(1, keyFrame.avcPacketType().intValue());
        assertNotNull(audioConfig);
        assertEquals(0, audioConfig.aacPacketType().intValue());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRespondToReleaseStream() {
        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(new StreamRegistry(), sessionManager));

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "releaseStream", 3.0d, null, Collections.<Object>singletonList("cam03")));

        RtmpCommandMessage result = channel.readOutbound();
        assertNotNull(result);
        assertEquals("_result", result.commandName());
        assertEquals(3.0d, result.transactionId());
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals("cam03", session.streamName());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRespondToFcPublish() {
        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(new StreamRegistry(), sessionManager));

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "FCPublish", 4.0d, null, Collections.<Object>singletonList("cam03")));

        RtmpCommandMessage result = channel.readOutbound();
        assertNotNull(result);
        assertEquals("_result", result.commandName());
        assertEquals(4.0d, result.transactionId());
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals("cam03", session.streamName());
        assertEquals(RtmpSessionRole.UNKNOWN, session.role());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemovePublishedStreamOnFcUnpublish() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(5, 0L, 1, "publish", 0.0d, null, Collections.<Object>singletonList("cam04")));
        channel.readOutbound();

        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam04");
        assertNotNull(registry.findPublishedStream(streamKey));

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "FCUnpublish", 4.0d, null, Collections.<Object>singletonList("cam04")));

        RtmpCommandMessage result = channel.readOutbound();
        assertNotNull(result);
        assertEquals("_result", result.commandName());
        assertNull(registry.findPublishedStream(streamKey));
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals(RtmpSessionRole.UNKNOWN, session.role());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldFanoutLiveFramesToPlaySubscriber() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTMP, "live", "cam02");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam02")));
        RtmpCommandMessage playStatus = channel.readOutbound();
        assertNotNull(playStatus);

        stream.onInboundFrame(new InboundMediaFrame(
                StreamProtocol.RTMP,
                TrackType.VIDEO,
                CodecType.H264,
                "publisher-2",
                streamKey,
                "video-h264",
                Long.valueOf(200L),
                Long.valueOf(200L),
                true,
                false,
                null,
                new byte[]{0x21, 0x22, 0x23}
        ));

        RtmpVideoMessage liveVideo = channel.readOutbound();
        assertNotNull(liveVideo);
        assertEquals(1, liveVideo.avcPacketType().intValue());
        assertEquals(8, liveVideo.payload().length);

        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "deleteStream", 0.0d, null, Collections.<Object>singletonList(Double.valueOf(1.0d))));
        RtmpSession session = sessionManager.sessions().iterator().next();
        assertEquals(RtmpSessionRole.UNKNOWN, session.role());

        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspH264RtpPacketsToRtmpVideoMessagesOnPlay() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam03");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                9,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                10,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam03")));

        RtmpCommandMessage playStatus = channel.readOutbound();
        RtmpVideoMessage config = channel.readOutbound();
        RtmpVideoMessage keyFrame = channel.readOutbound();
        assertNotNull(playStatus);
        assertEquals("onStatus", playStatus.commandName());
        assertNotNull(config);
        assertEquals(7, config.codecId());
        assertEquals(0, config.avcPacketType().intValue());
        assertNotNull(keyFrame);
        assertEquals(7, keyFrame.codecId());
        assertEquals(1, keyFrame.avcPacketType().intValue());
        assertEquals(1, keyFrame.frameType());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspH265RtpPacketsToRtmpVideoMessagesOnPlay() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam04");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                1,
                2000L,
                false,
                new byte[]{0x40, 0x01, 0x0C}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                2,
                2000L,
                false,
                new byte[]{0x42, 0x01, 0x01}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                3,
                2000L,
                false,
                new byte[]{0x44, 0x01, (byte) 0xC0}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                4,
                2000L,
                true,
                new byte[]{0x26, 0x01, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam04")));

        RtmpCommandMessage playStatus = channel.readOutbound();
        RtmpVideoMessage config = channel.readOutbound();
        RtmpVideoMessage keyFrame = channel.readOutbound();
        assertNotNull(playStatus);
        assertEquals("onStatus", playStatus.commandName());
        assertNotNull(config);
        assertEquals(12, config.codecId());
        assertEquals(0, config.avcPacketType().intValue());
        assertNotNull(keyFrame);
        assertEquals(12, keyFrame.codecId());
        assertEquals(1, keyFrame.avcPacketType().intValue());
        assertEquals(1, keyFrame.frameType());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspAacRtpPacketsToRtmpAudioMessagesAfterPlayStarts() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam05");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam05")));
        drainOutbound(channel, 3);

        stream.onInboundRtpPacket(aacRtpPacket(
                streamKey,
                "audio-aac",
                10,
                48000L,
                new byte[]{0x55, 0x66}
        ));

        RtmpAudioMessage config = channel.readOutbound();
        RtmpAudioMessage audio = channel.readOutbound();
        assertNotNull(config);
        assertEquals(10, config.soundFormat());
        assertEquals(0, config.aacPacketType().intValue());
        assertNotNull(audio);
        assertEquals(10, audio.soundFormat());
        assertEquals(1, audio.aacPacketType().intValue());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspG711RtpPacketsToRtmpAudioMessagesAfterPlayStarts() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam07");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam07")));
        drainOutbound(channel, 3);

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711a",
                CodecType.G711A,
                10,
                160L,
                new byte[]{0x55, 0x66, 0x77}
        ));

        RtmpAudioMessage audio = channel.readOutbound();
        assertNotNull(audio);
        assertEquals(7, audio.soundFormat());
        assertEquals(4, audio.payload().length);
        assertEquals(0x72, audio.payload()[0] & 0xFF);
        assertEquals(0x55, audio.payload()[1] & 0xFF);
        assertEquals(0x77, audio.payload()[3] & 0xFF);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspG711uRtpPacketsToRtmpAudioMessagesAfterPlayStarts() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam08");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam08")));
        drainOutbound(channel, 3);

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711u",
                CodecType.G711U,
                10,
                160L,
                new byte[]{0x41, 0x52, 0x63}
        ));

        RtmpAudioMessage audio = channel.readOutbound();
        assertNotNull(audio);
        assertEquals(8, audio.soundFormat());
        assertEquals(4, audio.payload().length);
        assertEquals(0x82, audio.payload()[0] & 0xFF);
        assertEquals(0x41, audio.payload()[1] & 0xFF);
        assertEquals(0x63, audio.payload()[3] & 0xFF);
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldKeepRtmpTimestampsContinuousForRtspH264AndG711aDuringPlay() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam09");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam09")));
        drainOutbound(channel, 3);

        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                4,
                4600L,
                true,
                new byte[]{0x41, 0x33, 0x44}
        ));
        RtmpVideoMessage video = channel.readOutbound();
        assertNotNull(video);
        assertEquals(40L, video.timestamp());
        assertEquals(1, video.avcPacketType().intValue());
        assertEquals(2, video.frameType());

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711a",
                CodecType.G711A,
                10,
                160L,
                new byte[]{0x55, 0x66}
        ));
        RtmpAudioMessage audio0 = channel.readOutbound();
        assertNotNull(audio0);
        assertEquals(0L, audio0.timestamp());
        assertEquals(7, audio0.soundFormat());

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711a",
                CodecType.G711A,
                11,
                320L,
                new byte[]{0x77, (byte) 0x88}
        ));
        RtmpAudioMessage audio20 = channel.readOutbound();
        assertNotNull(audio20);
        assertEquals(20L, audio20.timestamp());
        assertEquals(7, audio20.soundFormat());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldKeepRtmpTimestampsContinuousForRtspH265AndG711uDuringPlay() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam10");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                1,
                2000L,
                false,
                new byte[]{0x40, 0x01, 0x0C}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                2,
                2000L,
                false,
                new byte[]{0x42, 0x01, 0x01}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                3,
                2000L,
                false,
                new byte[]{0x44, 0x01, (byte) 0xC0}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                4,
                2000L,
                true,
                new byte[]{0x26, 0x01, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam10")));
        drainOutbound(channel, 3);

        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h265",
                CodecType.H265,
                TrackType.VIDEO,
                5,
                5600L,
                true,
                new byte[]{0x02, 0x01, 0x55, 0x66}
        ));
        RtmpVideoMessage video = channel.readOutbound();
        assertNotNull(video);
        assertEquals(40L, video.timestamp());
        assertEquals(1, video.avcPacketType().intValue());
        assertEquals(2, video.frameType());

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711u",
                CodecType.G711U,
                10,
                160L,
                new byte[]{0x41, 0x52}
        ));
        RtmpAudioMessage audio0 = channel.readOutbound();
        assertNotNull(audio0);
        assertEquals(0L, audio0.timestamp());
        assertEquals(8, audio0.soundFormat());

        stream.onInboundRtpPacket(g711RtpPacket(
                streamKey,
                "audio-g711u",
                CodecType.G711U,
                11,
                320L,
                new byte[]{0x63, 0x74}
        ));
        RtmpAudioMessage audio20 = channel.readOutbound();
        assertNotNull(audio20);
        assertEquals(20L, audio20.timestamp());
        assertEquals(8, audio20.soundFormat());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRemuxRtspH264WithSdpOnlyParameterSetsToRtmpVideoMessagesOnPlay() {
        RtspSessionManager rtspSessionManager = new RtspSessionManager();
        StreamRegistry registry = new StreamRegistry(rtspSessionManager);
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam06");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);

        RtspSession publisherSession = rtspSessionManager.register(new com.wenting.mediaserver.protocol.rtsp.RtspSession("publisher-rtsp"));
        publisherSession.role(com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole.PUBLISHER);
        publisherSession.streamKey(streamKey);
        publisherSession.sdpOrigin(
                "v=0\r\n"
                        + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                        + "s=No Name\r\n"
                        + "c=IN IP4 127.0.0.1\r\n"
                        + "t=0 0\r\n"
                        + "m=video 0 RTP/AVP 96\r\n"
                        + "a=rtpmap:96 H264/90000\r\n"
                        + "a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z2QAH6zZQHg=,aO48gA==\r\n"
                        + "a=control:video-h264\r\n"
        );

        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));

        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam06")));

        RtmpCommandMessage playStatus = channel.readOutbound();
        RtmpVideoMessage config = channel.readOutbound();
        RtmpVideoMessage keyFrame = channel.readOutbound();
        assertNotNull(playStatus);
        assertNotNull(config);
        assertEquals(0, config.avcPacketType().intValue());
        assertNotNull(keyFrame);
        assertEquals(1, keyFrame.avcPacketType().intValue());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldNotEmitRtmpVideoWhenRtspH264FuAIsIncomplete() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam12");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));
        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(channel, 4);
        channel.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(channel, 1);
        channel.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam12")));
        drainOutbound(channel, 1);

        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                10,
                4600L,
                false,
                new byte[]{0x7C, (byte) 0x85, 0x11, 0x22}
        ));

        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReplayCachedRtspVideoOnRtmpSubscriberReconnect() {
        StreamRegistry registry = new StreamRegistry(new RtspSessionManager());
        StreamKey streamKey = new StreamKey(StreamProtocol.RTSP, "live", "cam13");
        DefaultPublishedStream stream = new DefaultPublishedStream(streamKey);
        registry.registerPublishedStream(streamKey, stream);
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                1,
                1000L,
                false,
                new byte[]{0x67, 0x64, 0x00, 0x1F, (byte) 0xAC, (byte) 0xD9, 0x40, 0x78}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                2,
                1000L,
                false,
                new byte[]{0x68, (byte) 0xEE, 0x3C, (byte) 0x80}
        ));
        stream.onInboundRtpPacket(rtpPacket(
                streamKey,
                "video-h264",
                CodecType.H264,
                TrackType.VIDEO,
                3,
                1000L,
                true,
                new byte[]{0x65, 0x11, 0x22}
        ));

        RtmpSessionManager sessionManager = new RtmpSessionManager();
        EmbeddedChannel first = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));
        Map<String, Object> connectObject = new LinkedHashMap<String, Object>();
        connectObject.put("app", "live");
        first.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(first, 4);
        first.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(first, 1);
        first.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam13")));
        drainOutbound(first, 3);
        first.close();

        EmbeddedChannel second = new EmbeddedChannel(new RtmpConnectionHandler(registry, sessionManager));
        second.writeInbound(new RtmpCommandMessage(3, 0L, 0, "connect", 1.0d, connectObject, Collections.<Object>emptyList()));
        drainOutbound(second, 4);
        second.writeInbound(new RtmpCommandMessage(3, 0L, 0, "createStream", 2.0d, null, Collections.<Object>emptyList()));
        drainOutbound(second, 1);
        second.writeInbound(new RtmpCommandMessage(8, 0L, 1, "play", 0.0d, null, Collections.<Object>singletonList("cam13")));

        RtmpCommandMessage playStatus = second.readOutbound();
        RtmpVideoMessage config = second.readOutbound();
        RtmpVideoMessage keyFrame = second.readOutbound();
        assertNotNull(playStatus);
        assertNotNull(config);
        assertEquals(0, config.avcPacketType().intValue());
        assertNotNull(keyFrame);
        assertEquals(1, keyFrame.avcPacketType().intValue());
        second.finishAndReleaseAll();
    }

    private static void drainOutbound(EmbeddedChannel channel, int count) {
        for (int i = 0; i < count; i++) {
            Object ignored = channel.readOutbound();
            assertNotNull(ignored);
        }
    }

    private static InboundRtpPacket rtpPacket(
            StreamKey streamKey,
            String trackId,
            CodecType codecType,
            TrackType trackType,
            int sequenceNumber,
            long timestamp,
            boolean marker,
            byte[] rtpPayload
    ) {
        return rtpPacket(streamKey, trackId, codecType, trackType, sequenceNumber, timestamp, marker, rtpPayload,
                MediaPacketTransport.TCP_INTERLEAVED, null, null);
    }

    private static InboundRtpPacket rtpPacket(
            StreamKey streamKey,
            String trackId,
            CodecType codecType,
            TrackType trackType,
            int sequenceNumber,
            long timestamp,
            boolean marker,
            byte[] rtpPayload,
            MediaPacketTransport transport,
            Integer localPort,
            java.net.SocketAddress remoteAddress
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
                        false,
                        (InetSocketAddress) remoteAddress,
                        packet
                ),
                resolveClockRate(trackType, codecType),
                false,
                transport,
                localPort,
                Integer.valueOf(0)
        );
    }

    private static InboundRtpPacket aacRtpPacket(
            StreamKey streamKey,
            String trackId,
            int sequenceNumber,
            long timestamp,
            byte[] aacFrame
    ) {
        byte[] rtpPayload = new byte[4 + aacFrame.length];
        rtpPayload[0] = 0x00;
        rtpPayload[1] = 0x10;
        int sizeBits = aacFrame.length << 3;
        rtpPayload[2] = (byte) ((sizeBits >> 8) & 0xFF);
        rtpPayload[3] = (byte) (sizeBits & 0xFF);
        System.arraycopy(aacFrame, 0, rtpPayload, 4, aacFrame.length);
        return rtpPacket(streamKey, trackId, CodecType.AAC, TrackType.AUDIO, sequenceNumber, timestamp, true, rtpPayload);
    }

    private static InboundRtpPacket g711RtpPacket(
            StreamKey streamKey,
            String trackId,
            CodecType codecType,
            int sequenceNumber,
            long timestamp,
            byte[] audioFrame
    ) {
        return rtpPacket(streamKey, trackId, codecType, TrackType.AUDIO, sequenceNumber, timestamp, true, audioFrame);
    }

    private static int resolveClockRate(TrackType trackType, CodecType codecType) {
        if (trackType == TrackType.AUDIO) {
            if (codecType == CodecType.AAC || codecType == CodecType.MPEG4_GENERIC) {
                return 48000;
            }
            if (codecType == CodecType.G711A || codecType == CodecType.G711U) {
                return 8000;
            }
        }
        return 90000;
    }
}
