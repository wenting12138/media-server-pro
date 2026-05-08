package com.wenting.mediaserver.protocol.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.RtmpHandshakeHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RtmpHandshakeHandlerTest {

    @Test
    void shouldRespondToSimpleHandshakeAndRemoveItselfAfterC2() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtmpHandshakeHandler());
        ByteBuf c0c1 = Unpooled.buffer(1537);
        c0c1.writeByte(3);
        c0c1.writeZero(1536);

        channel.writeInbound(c0c1.retain());
        ByteBuf s0s1s2 = channel.readOutbound();
        assertEquals(3073, s0s1s2.readableBytes());
        s0s1s2.release();

        ByteBuf c2 = Unpooled.buffer(1536);
        c2.writeZero(1536);
        channel.writeInbound(c2.retain());

        assertNull(channel.pipeline().get(RtmpHandshakeHandler.class));
        channel.finishAndReleaseAll();
    }
}
