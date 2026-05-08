package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class RtmpMessageEncoder extends MessageToByteEncoder<RtmpMessage> {

    private int outboundChunkSize = 128;

    @Override
    protected void encode(ChannelHandlerContext ctx, RtmpMessage msg, ByteBuf out) {
        byte[] payload = msg.payload();
        int offset = 0;
        long timestamp = msg.timestamp();
        boolean extendedTimestamp = timestamp >= 0xFFFFFFL;
        while (offset < payload.length || (payload.length == 0 && offset == 0)) {
            int chunkLength = Math.min(outboundChunkSize, payload.length - offset);
            if (payload.length == 0) {
                chunkLength = 0;
            }
            writeBasicHeader(out, offset == 0 ? 0 : 3, msg.chunkStreamId());
            if (offset == 0) {
                writeMedium(out, extendedTimestamp ? 0xFFFFFF : (int) timestamp);
                writeMedium(out, payload.length);
                out.writeByte(msg.messageTypeId());
                writeLittleEndianInt(out, msg.messageStreamId());
            }
            if (extendedTimestamp) {
                out.writeInt((int) timestamp);
            }
            if (chunkLength > 0) {
                out.writeBytes(payload, offset, chunkLength);
            }
            offset += chunkLength;
            if (payload.length == 0) {
                break;
            }
        }
        if (msg instanceof RtmpSetChunkSizeMessage) {
            outboundChunkSize = Math.max(128, ((RtmpSetChunkSizeMessage) msg).chunkSize());
        }
    }

    private void writeBasicHeader(ByteBuf out, int format, int chunkStreamId) {
        if (chunkStreamId >= 2 && chunkStreamId <= 63) {
            out.writeByte((format << 6) | chunkStreamId);
            return;
        }
        if (chunkStreamId <= 319) {
            out.writeByte(format << 6);
            out.writeByte(chunkStreamId - 64);
            return;
        }
        out.writeByte((format << 6) | 1);
        int encoded = chunkStreamId - 64;
        out.writeByte(encoded & 0xFF);
        out.writeByte((encoded >> 8) & 0xFF);
    }

    private void writeMedium(ByteBuf out, int value) {
        out.writeByte((value >> 16) & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
        out.writeByte(value & 0xFF);
    }

    private void writeLittleEndianInt(ByteBuf out, int value) {
        out.writeByte(value & 0xFF);
        out.writeByte((value >> 8) & 0xFF);
        out.writeByte((value >> 16) & 0xFF);
        out.writeByte((value >> 24) & 0xFF);
    }
}
