package com.wenting.mediaserver.core.codec.rtsp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Demultiplexes RTSP-over-TCP: plain RTSP text messages and interleaved binary ($ ch len payload).
 */
public final class RtspTcpFramingDecoder extends ByteToMessageDecoder {

    private static final byte DOLLAR = 0x24;
    private static final int INTERLEAVED_HEADER_SIZE = 4;
    private static final int CRLF_SIZE = 2;
    private static final int DOUBLE_CRLF_SIZE = 4;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (in.isReadable()) {
            if (startsWithInterleavedFrame(in)) {
                if (!decodeInterleavedFrame(in, out)) {
                    return;
                }
            } else {
                RtspRequestMessage message = decodeRtspMessage(in);
                if (message == null) {
                    return;
                }
                out.add(message);
            }
        }
    }

    /**
     * @return parsed message or null if incomplete; advances {@code in} on success
     */
    private RtspRequestMessage decodeRtspMessage(ByteBuf in) {
        int startRidx = in.readerIndex();
        int searchStart = startRidx;
        int writer = in.writerIndex();
        int hdr = indexOfDoubleCrlf(in, searchStart, writer);
        if (hdr < 0) {
            return null;
        }
        int bodyStart = hdr + DOUBLE_CRLF_SIZE;
        int contentLen = parseContentLength(in, searchStart, hdr + CRLF_SIZE);
        int need = bodyStart + contentLen;
        if (writer < need) {
            return null;
        }
        int totalLen = need - startRidx;
        ByteBuf composite = in.retainedSlice(startRidx, totalLen);
        in.skipBytes(totalLen);
        return RtspRequestMessage.parse(composite);
    }

    private boolean decodeInterleavedFrame(ByteBuf in, List<Object> out) {
        int frameStart = in.readerIndex();
        if (in.readableBytes() < INTERLEAVED_HEADER_SIZE) {
            return false;
        }

        in.skipBytes(1);
        int channel = in.readUnsignedByte();
        int payloadLength = in.readUnsignedShort();
        if (in.readableBytes() < payloadLength) {
            in.readerIndex(frameStart);
            return false;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLength);
        out.add(new InterleavedRtpPacket(channel, payload));
        return true;
    }

    private static boolean startsWithInterleavedFrame(ByteBuf in) {
        return in.getByte(in.readerIndex()) == DOLLAR;
    }

    private static int parseContentLength(ByteBuf in, int from, int hdrEnd) {
        int idx = from;
        while (idx < hdrEnd) {
            int lineEnd = indexOfCrlf(in, idx, hdrEnd);
            if (lineEnd < 0) {
                break;
            }
            String line = in.toString(idx, lineEnd - idx, io.netty.util.CharsetUtil.US_ASCII);
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                if ("content-length".equals(name)) {
                    try {
                        return Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
            idx = lineEnd + CRLF_SIZE;
        }
        return 0;
    }

    private static int indexOfCrlf(ByteBuf buf, int start, int end) {
        for (int i = start; i + 1 < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfDoubleCrlf(ByteBuf buf, int start, int end) {
        for (int i = start; i + 3 < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                return i;
            }
        }
        return -1;
    }

}
