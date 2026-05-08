package com.wenting.mediaserver.core.codec.rtmp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RtmpChunkDecoder extends ByteToMessageDecoder {

    private final Map<Integer, RtmpChunkStreamState> chunkStreamStates = new HashMap<Integer, RtmpChunkStreamState>();
    private final RtmpMessageFactory messageFactory = new RtmpMessageFactory();
    private int inboundChunkSize = 128;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            in.markReaderIndex();
            RtmpChunkHeader header = readHeader(in);
            if (header == null) {
                in.resetReaderIndex();
                return;
            }
            RtmpChunkStreamState state = chunkStreamStates.get(Integer.valueOf(header.chunkStreamId()));
            if (state == null) {
                state = new RtmpChunkStreamState(header.chunkStreamId());
                chunkStreamStates.put(Integer.valueOf(header.chunkStreamId()), state);
            }
            RtmpChunkMessage message = resolveCurrentMessage(state, header);
            int toRead = Math.min(inboundChunkSize, message.remaining());
            if (in.readableBytes() < toRead) {
                in.resetReaderIndex();
                return;
            }
            applyHeaderState(state, header);
            message.append(in, toRead);
            state.currentMessage(message);
            if (!message.complete()) {
                continue;
            }
            state.currentMessage(null);
            RtmpMessage decodedMessage = messageFactory.create(
                    message.chunkStreamId(),
                    message.timestamp(),
                    message.messageStreamId(),
                    message.messageTypeId(),
                    message.payload()
            );
            if (decodedMessage instanceof RtmpSetChunkSizeMessage) {
                inboundChunkSize = Math.max(128, ((RtmpSetChunkSizeMessage) decodedMessage).chunkSize());
            }
            out.add(decodedMessage);
        }
    }

    private RtmpChunkHeader readHeader(ByteBuf in) {
        if (!in.isReadable()) {
            return null;
        }
        int first = in.readUnsignedByte();
        int format = (first >> 6) & 0x03;
        int chunkStreamId = first & 0x3F;
        if (chunkStreamId == 0) {
            if (in.readableBytes() < 1) {
                return null;
            }
            chunkStreamId = 64 + in.readUnsignedByte();
        } else if (chunkStreamId == 1) {
            if (in.readableBytes() < 2) {
                return null;
            }
            chunkStreamId = 64 + in.readUnsignedByte() + (in.readUnsignedByte() << 8);
        }
        RtmpChunkStreamState previousState = chunkStreamStates.get(Integer.valueOf(chunkStreamId));
        if (format > 0 && previousState == null) {
            throw new IllegalStateException("Missing previous chunk header for csid=" + chunkStreamId);
        }
        long timestamp = 0L;
        long timestampDelta = 0L;
        int messageLength = previousState == null ? 0 : previousState.lastMessageLength();
        int messageTypeId = previousState == null ? 0 : previousState.lastMessageTypeId();
        int messageStreamId = previousState == null ? 0 : previousState.lastMessageStreamId();
        boolean extendedTimestamp = false;
        boolean continuation = false;
        if (format == 0) {
            if (in.readableBytes() < 11) {
                return null;
            }
            long rawTimestamp = readUnsignedMedium(in);
            messageLength = (int) readUnsignedMedium(in);
            messageTypeId = in.readUnsignedByte();
            messageStreamId = readLittleEndianInt(in);
            if (rawTimestamp == 0xFFFFFFL) {
                if (in.readableBytes() < 4) {
                    return null;
                }
                timestamp = in.readUnsignedInt();
                extendedTimestamp = true;
            } else {
                timestamp = rawTimestamp;
            }
        } else if (format == 1) {
            if (in.readableBytes() < 7) {
                return null;
            }
            long rawDelta = readUnsignedMedium(in);
            messageLength = (int) readUnsignedMedium(in);
            messageTypeId = in.readUnsignedByte();
            if (rawDelta == 0xFFFFFFL) {
                if (in.readableBytes() < 4) {
                    return null;
                }
                timestampDelta = in.readUnsignedInt();
                extendedTimestamp = true;
            } else {
                timestampDelta = rawDelta;
            }
            timestamp = previousState.lastTimestamp() + timestampDelta;
            messageStreamId = previousState.lastMessageStreamId();
        } else if (format == 2) {
            if (in.readableBytes() < 3) {
                return null;
            }
            long rawDelta = readUnsignedMedium(in);
            if (rawDelta == 0xFFFFFFL) {
                if (in.readableBytes() < 4) {
                    return null;
                }
                timestampDelta = in.readUnsignedInt();
                extendedTimestamp = true;
            } else {
                timestampDelta = rawDelta;
            }
            timestamp = previousState.lastTimestamp() + timestampDelta;
        } else {
            continuation = previousState.currentMessage() != null && !previousState.currentMessage().complete();
            timestampDelta = previousState.lastTimestampDelta();
            timestamp = continuation ? previousState.currentMessage().timestamp() : previousState.lastTimestamp() + timestampDelta;
            if (previousState.lastExtendedTimestamp()) {
                if (in.readableBytes() < 4) {
                    return null;
                }
                long extended = in.readUnsignedInt();
                if (!continuation) {
                    timestamp = extended;
                }
                extendedTimestamp = true;
            }
        }
        return new RtmpChunkHeader(
                format,
                chunkStreamId,
                timestamp,
                timestampDelta,
                messageLength,
                messageTypeId,
                messageStreamId,
                extendedTimestamp,
                continuation
        );
    }

    private void applyHeaderState(RtmpChunkStreamState state, RtmpChunkHeader header) {
        state.lastTimestamp(header.timestamp());
        state.lastTimestampDelta(header.timestampDelta());
        state.lastMessageLength(header.messageLength());
        state.lastMessageTypeId(header.messageTypeId());
        state.lastMessageStreamId(header.messageStreamId());
        state.lastExtendedTimestamp(header.extendedTimestamp());
    }

    private RtmpChunkMessage resolveCurrentMessage(RtmpChunkStreamState state, RtmpChunkHeader header) {
        if (header.continuation() && state.currentMessage() != null) {
            return state.currentMessage();
        }
        return new RtmpChunkMessage(
                header.chunkStreamId(),
                header.timestamp(),
                header.messageStreamId(),
                header.messageTypeId(),
                header.messageLength()
        );
    }

    private long readUnsignedMedium(ByteBuf in) {
        return ((long) in.readUnsignedByte() << 16)
                | ((long) in.readUnsignedByte() << 8)
                | (long) in.readUnsignedByte();
    }

    private int readLittleEndianInt(ByteBuf in) {
        return in.readUnsignedByte()
                | (in.readUnsignedByte() << 8)
                | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 24);
    }
}
