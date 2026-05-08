package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class RtmpHandshakeHandler extends ByteToMessageDecoder {

    private static final int HANDSHAKE_SIZE = 1536;

    private int state;
    private byte[] c1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (state == 0) {
            if (in.readableBytes() < HANDSHAKE_SIZE + 1) {
                return;
            }
            short version = in.readUnsignedByte();
            if (version != 3) {
                throw new IllegalArgumentException("Unsupported RTMP version: " + version);
            }
            c1 = new byte[HANDSHAKE_SIZE];
            in.readBytes(c1);
            ctx.writeAndFlush(buildHandshakeResponse(c1));
            state = 1;
        }
        if (state == 1) {
            if (in.readableBytes() < HANDSHAKE_SIZE) {
                return;
            }
            in.skipBytes(HANDSHAKE_SIZE);
            state = 2;
            ctx.pipeline().remove(this);
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
        }
    }

    private ByteBuf buildHandshakeResponse(byte[] c1Payload) {
        ByteBuf buffer = Unpooled.buffer(1 + HANDSHAKE_SIZE + HANDSHAKE_SIZE);
        buffer.writeByte(3);
        buffer.writeInt((int) (System.currentTimeMillis() / 1000L));
        buffer.writeInt(0);
        byte[] random = new byte[HANDSHAKE_SIZE - 8];
        ThreadLocalRandom.current().nextBytes(random);
        buffer.writeBytes(random);
        buffer.writeBytes(c1Payload);
        return buffer;
    }
}
