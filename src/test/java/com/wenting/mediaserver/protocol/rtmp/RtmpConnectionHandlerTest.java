package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.DefaultPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.core.codec.rtmp.RtmpAudioMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpCommandMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import com.wenting.mediaserver.protocol.rtsp.RtspSessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtmpConnectionHandlerTest {

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

    private static void drainOutbound(EmbeddedChannel channel, int count) {
        for (int i = 0; i < count; i++) {
            Object ignored = channel.readOutbound();
            assertNotNull(ignored);
        }
    }
}
