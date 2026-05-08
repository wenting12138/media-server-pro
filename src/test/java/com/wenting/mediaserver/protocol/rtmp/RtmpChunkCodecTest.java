package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.RtmpChunkDecoder;
import com.wenting.mediaserver.core.codec.rtmp.RtmpCommandMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpDataMessage;
import com.wenting.mediaserver.core.codec.rtmp.RtmpMessageEncoder;
import com.wenting.mediaserver.core.codec.rtmp.RtmpVideoMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtmpChunkCodecTest {

    @Test
    void shouldRoundTripCommandMessageThroughChunkCodec() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpChunkDecoder(), new RtmpMessageEncoder());
        Map<String, Object> commandObject = new LinkedHashMap<String, Object>();
        commandObject.put("app", "live");
        commandObject.put("tcUrl", "rtmp://example/live");

        RtmpCommandMessage outbound = new RtmpCommandMessage(
                3,
                0L,
                0,
                "connect",
                1.0d,
                commandObject,
                Collections.<Object>emptyList()
        );

        assertTrue(channel.writeOutbound(outbound));
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);

        assertTrue(channel.writeInbound(encoded.retain()));
        RtmpCommandMessage inbound = channel.readInbound();
        assertNotNull(inbound);
        assertEquals("connect", inbound.commandName());
        assertEquals(1.0d, inbound.transactionId());
        assertEquals("live", ((Map<?, ?>) inbound.commandObject()).get("app"));
        encoded.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldDecodeVideoAndDataMessageMetadata() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpChunkDecoder(), new RtmpMessageEncoder());

        byte[] videoPayload = new byte[]{0x17, 0x01, 0x00, 0x00, 0x05, 0x11, 0x22};
        RtmpVideoMessage videoMessage = new RtmpVideoMessage(6, 42L, 1, videoPayload);
        assertTrue(channel.writeOutbound(videoMessage));
        ByteBuf encodedVideo = channel.readOutbound();
        assertTrue(channel.writeInbound(encodedVideo.retain()));
        RtmpVideoMessage inboundVideo = channel.readInbound();
        assertEquals(1, inboundVideo.frameType());
        assertEquals(7, inboundVideo.codecId());
        assertEquals(Integer.valueOf(1), inboundVideo.avcPacketType());
        assertEquals(Integer.valueOf(5), inboundVideo.compositionTime());
        encodedVideo.release();

        RtmpDataMessage dataMessage = new RtmpDataMessage(4, 0L, 1, Arrays.<Object>asList("@setDataFrame", "onMetaData"));
        assertTrue(channel.writeOutbound(dataMessage));
        ByteBuf encodedData = channel.readOutbound();
        assertTrue(channel.writeInbound(encodedData.retain()));
        RtmpDataMessage inboundData = channel.readInbound();
        assertEquals("@setDataFrame", inboundData.values().get(0));
        assertEquals("onMetaData", inboundData.values().get(1));
        encodedData.release();

        channel.finishAndReleaseAll();
    }
}
