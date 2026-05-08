package com.wenting.mediaserver.core.codec.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspTcpFramingDecoderTest {

    @Test
    void shouldDecodeRtspRequestWithoutBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspTcpFramingDecoder());
        ByteBuf request = ascii(
                "OPTIONS rtsp://example/live RTSP/1.0\r\n"
                        + "CSeq: 7\r\n"
                        + "User-Agent: test-client\r\n"
                        + "\r\n"
        );

        assertTrue(channel.writeInbound(request));

        RtspRequestMessage message = channel.readInbound();
        assertNotNull(message);
        assertEquals("OPTIONS", message.method());
        assertEquals("rtsp://example/live", message.uri());
        assertEquals("RTSP/1.0", message.version());
        assertEquals("7", message.header("CSeq"));
        assertEquals("test-client", message.header("user-agent"));
        assertEquals(0, message.body().readableBytes());
        assertNull(channel.readInbound());
        release(message);
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldWaitForFullRtspBodyBeforeEmittingMessage() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspTcpFramingDecoder());

        assertFalse(channel.writeInbound(ascii(
                "ANNOUNCE rtsp://example/live RTSP/1.0\r\n"
                        + "CSeq: 2\r\n"
                        + "Content-Length: 4\r\n"
                        + "\r\n"
                        + "ab"
        )));
        assertNull(channel.readInbound());

        assertTrue(channel.writeInbound(ascii("cd")));

        RtspRequestMessage message = channel.readInbound();
        assertNotNull(message);
        assertEquals("ANNOUNCE", message.method());
        assertEquals("abcd", message.body().toString(CharsetUtil.US_ASCII));
        release(message);
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldDecodeInterleavedFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspTcpFramingDecoder());
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte('$');
        frame.writeByte(2);
        frame.writeShort(4);
        frame.writeBytes(new byte[]{0x11, 0x22, 0x33, 0x44});

        assertTrue(channel.writeInbound(frame));

        InterleavedRtpPacket packet = channel.readInbound();
        assertNotNull(packet);
        assertEquals(2, packet.channel());
        assertEquals(4, packet.payload().readableBytes());
        assertEquals(0x11, packet.payload().readUnsignedByte());
        assertEquals(0x22, packet.payload().readUnsignedByte());
        assertEquals(0x33, packet.payload().readUnsignedByte());
        assertEquals(0x44, packet.payload().readUnsignedByte());
        assertNull(channel.readInbound());
        packet.release();
        assertFalse(channel.finishAndReleaseAll());
    }

    @Test
    void shouldDecodeMixedRtspAndInterleavedFramesFromSingleBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new RtspTcpFramingDecoder());
        ByteBuf input = Unpooled.wrappedBuffer(
                ascii(
                        "OPTIONS rtsp://example/live RTSP/1.0\r\n"
                                + "CSeq: 9\r\n"
                                + "\r\n"
                ),
                interleavedFrame(1, new byte[]{0x55, 0x66})
        );

        assertTrue(channel.writeInbound(input));

        Object first = channel.readInbound();
        assertInstanceOf(RtspRequestMessage.class, first);
        RtspRequestMessage message = (RtspRequestMessage) first;
        assertEquals(9, message.cSeq());

        Object second = channel.readInbound();
        assertInstanceOf(InterleavedRtpPacket.class, second);
        InterleavedRtpPacket packet = (InterleavedRtpPacket) second;
        assertEquals(1, packet.channel());
        assertEquals(0x55, packet.payload().readUnsignedByte());
        assertEquals(0x66, packet.payload().readUnsignedByte());

        release(message);
        packet.release();
        assertNull(channel.readInbound());
        assertFalse(channel.finishAndReleaseAll());
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, CharsetUtil.US_ASCII);
    }

    private static ByteBuf interleavedFrame(int channel, byte[] payload) {
        ByteBuf frame = Unpooled.buffer(4 + payload.length);
        frame.writeByte('$');
        frame.writeByte(channel);
        frame.writeShort(payload.length);
        frame.writeBytes(payload);
        return frame;
    }

    private static void release(RtspRequestMessage message) {
        if (message != null && message.body().refCnt() > 0) {
            message.body().release();
        }
    }
}
