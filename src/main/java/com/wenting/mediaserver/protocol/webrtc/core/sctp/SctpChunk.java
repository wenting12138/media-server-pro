package com.wenting.mediaserver.protocol.webrtc.core.sctp;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;

/**
 * SCTP chunk types encode/decode (RFC 4960 Section 3.2).
 */
public abstract class SctpChunk {

    protected final int type;
    protected final byte flags;

    SctpChunk(int type, byte flags) {
        this.type = type;
        this.flags = flags;
    }

    public int getType() { return type; }

    /** Encode chunk data (excluding the 4-byte header) */
    protected abstract void encodeData(ByteBuffer buf);

    public byte[] encode() {
        // Temporarily encode to compute length (pad to 4-byte boundary)
        byte[] data = encodeDataWithPadding();
        int paddedLen = data.length + CHUNK_HEADER_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(paddedLen);
        buf.put((byte) type);
        buf.put(flags);
        buf.putShort((short) paddedLen);
        buf.put(data);
        return buf.array();
    }

    private byte[] encodeDataWithPadding() {
        byte[] data = encodeDataBytes();
        int pad = (4 - (data.length % 4)) % 4;
        if (pad == 0) return data;
        byte[] padded = Arrays.copyOf(data, data.length + pad);
        return padded;
    }

    private byte[] encodeDataBytes() {
        ByteBuffer buf = ByteBuffer.allocate(65536);
        encodeData(buf);
        buf.flip();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    // ---- 解码 ----

    public static SctpChunk decode(byte[] data, int offset) {
        ByteBuffer buf = ByteBuffer.wrap(data, offset, data.length - offset);
        int type = buf.get() & 0xFF;
        byte flags = buf.get();
        int length = buf.getShort() & 0xFFFF;

        if (length < CHUNK_HEADER_SIZE) return null;

        int dataLen = length - CHUNK_HEADER_SIZE;
        byte[] chunkData = new byte[dataLen];
        if (dataLen > 0) buf.get(chunkData);

        switch (type) {
            case DATA:          return Data.decode(flags, chunkData);
            case INIT:          return Init.decode(flags, chunkData);
            case INIT_ACK:      return InitAck.decode(flags, chunkData);
            case SACK:          return Sack.decode(flags, chunkData);
            case COOKIE_ECHO: {
                // Strip trailing zero padding
                int len = chunkData.length;
                while (len > 0 && chunkData[len - 1] == 0) len--;
                byte[] trimmed = len < chunkData.length ? Arrays.copyOf(chunkData, len) : chunkData;
                return new CookieEcho(trimmed);
            }
            case COOKIE_ACK:    return new CookieAck();
            default:
                return new UnknownChunk(type, flags, chunkData);
        }
    }

    // ========== 具体 Chunk 类型 ==========

    /**
     * DATA chunk (RFC 4960 Section 3.3.1).
     */
    public static class Data extends SctpChunk {
        public final long tsn;            // 32-bit
        public final int streamId;         // 16-bit
        public final int streamSeq;        // 16-bit
        public final long ppid;            // 32-bit
        public final byte[] userData;
        public final boolean unordered;
        public final boolean begin;
        public final boolean end;

        Data(long tsn, int streamId, int streamSeq, long ppid,
             byte[] userData, boolean unordered, boolean begin, boolean end) {
            super(DATA, makeFlags(unordered, begin, end));
            this.tsn = tsn;
            this.streamId = streamId;
            this.streamSeq = streamSeq;
            this.ppid = ppid;
            this.userData = userData;
            this.unordered = unordered;
            this.begin = begin;
            this.end = end;
        }

        private static byte makeFlags(boolean unordered, boolean begin, boolean end) {
            byte f = 0;
            if (unordered) f |= DATA_FLAG_UNORDERED;
            if (begin) f |= DATA_FLAG_BEGIN;
            if (end) f |= DATA_FLAG_END;
            return f;
        }

        public static Data create(long tsn, int streamId, int streamSeq,
                                   long ppid, byte[] userData, boolean unordered) {
            return new Data(tsn, streamId, streamSeq, ppid, userData, unordered, true, true);
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.putInt((int) tsn);
            buf.putShort((short) streamId);
            buf.putShort((short) streamSeq);
            buf.putInt((int) ppid);
            buf.put(userData);
        }

        static Data decode(byte flags, byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long tsn = buf.getInt() & 0xFFFFFFFFL;
            int sid = buf.getShort() & 0xFFFF;
            int sSeq = buf.getShort() & 0xFFFF;
            long ppid = buf.getInt() & 0xFFFFFFFFL;
            byte[] payload = new byte[buf.remaining()];
            buf.get(payload);
            // Strip trailing zero padding (SCTP 4-byte alignment)
            while (payload.length > 0 && payload[payload.length - 1] == 0) {
                payload = Arrays.copyOf(payload, payload.length - 1);
            }
            return new Data(tsn, sid, sSeq, ppid, payload,
                (flags & DATA_FLAG_UNORDERED) != 0,
                (flags & DATA_FLAG_BEGIN) != 0,
                (flags & DATA_FLAG_END) != 0);
        }
    }

    /**
     * INIT chunk (RFC 4960 Section 3.3.2).
     */
    public static class Init extends SctpChunk {
        public final long initiateTag;         // 32-bit
        public final long advertisedRwnd;      // 32-bit
        public final int os;                   // 16-bit outbound streams
        public final int miss;                 // 16-bit inbound streams
        public final long initialTsn;          // 32-bit

        public Init(long initiateTag, long advertisedRwnd, int os, int miss, long initialTsn) {
            super(INIT, (byte) 0);
            this.initiateTag = initiateTag;
            this.advertisedRwnd = advertisedRwnd;
            this.os = os;
            this.miss = miss;
            this.initialTsn = initialTsn;
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.putInt((int) initiateTag);
            buf.putInt((int) advertisedRwnd);
            buf.putShort((short) os);
            buf.putShort((short) miss);
            buf.putInt((int) initialTsn);
        }

        static Init decode(byte flags, byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long tag = buf.getInt() & 0xFFFFFFFFL;
            long rwnd = buf.getInt() & 0xFFFFFFFFL;
            int os = buf.getShort() & 0xFFFF;
            int miss = buf.getShort() & 0xFFFF;
            long tsn = buf.getInt() & 0xFFFFFFFFL;
            return new Init(tag, rwnd, os, miss, tsn);
        }
    }

    /**
     * INIT-ACK chunk (RFC 4960 Section 3.3.3).
     */
    public static class InitAck extends SctpChunk {
        public final long initiateTag;
        public final long advertisedRwnd;
        public final int os;
        public final int miss;
        public final long initialTsn;
        public final byte[] stateCookie;

        public InitAck(long initiateTag, long advertisedRwnd, int os, int miss,
                       long initialTsn, byte[] stateCookie) {
            super(INIT_ACK, (byte) 0);
            this.initiateTag = initiateTag;
            this.advertisedRwnd = advertisedRwnd;
            this.os = os;
            this.miss = miss;
            this.initialTsn = initialTsn;
            this.stateCookie = stateCookie;
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.putInt((int) initiateTag);
            buf.putInt((int) advertisedRwnd);
            buf.putShort((short) os);
            buf.putShort((short) miss);
            buf.putInt((int) initialTsn);
            // State Cookie parameter: type=7, length=value.length+4, value
            buf.putShort((short) 7);            // Parameter type
            buf.putShort((short) (stateCookie.length + 4));
            buf.put(stateCookie);
        }

        static InitAck decode(byte flags, byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long tag = buf.getInt() & 0xFFFFFFFFL;
            long rwnd = buf.getInt() & 0xFFFFFFFFL;
            int os = buf.getShort() & 0xFFFF;
            int miss = buf.getShort() & 0xFFFF;
            long tsn = buf.getInt() & 0xFFFFFFFFL;
            byte[] cookie = new byte[0];
            while (buf.remaining() >= 4) {
                int paramType = buf.getShort() & 0xFFFF;
                int paramLen = buf.getShort() & 0xFFFF;
                if (paramType == 7) { // State Cookie
                    cookie = new byte[paramLen - 4];
                    buf.get(cookie);
                } else if (paramLen >= 4) {
                    buf.position(buf.position() + paramLen - 4);
                }
            }
            return new InitAck(tag, rwnd, os, miss, tsn, cookie);
        }
    }

    /**
     * COOKIE ECHO chunk (RFC 4960 Section 3.3.11).
     */
    public static class CookieEcho extends SctpChunk {
        public final byte[] cookie;

        public CookieEcho(byte[] cookie) {
            super(COOKIE_ECHO, (byte) 0);
            this.cookie = cookie;
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.put(cookie);
        }
    }

    /**
     * COOKIE ACK chunk (RFC 4960 Section 3.3.12).
     */
    public static class CookieAck extends SctpChunk {
        public CookieAck() {
            super(COOKIE_ACK, (byte) 0);
        }

        @Override
        protected void encodeData(ByteBuffer buf) {}
    }

    /**
     * SACK chunk (RFC 4960 Section 3.3.4).
     */
    public static class Sack extends SctpChunk {
        public final long cumulativeTsnAck;
        public final long advertisedRwnd;

        public Sack(long cumulativeTsnAck, long advertisedRwnd) {
            super(SACK, (byte) 0);
            this.cumulativeTsnAck = cumulativeTsnAck;
            this.advertisedRwnd = advertisedRwnd;
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.putInt((int) cumulativeTsnAck);
            buf.putInt((int) advertisedRwnd);
            buf.putShort((short) 0); // No gap ACK blocks
            buf.putShort((short) 0); // No duplicate TSNs
        }

        static Sack decode(byte flags, byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            long ack = buf.getInt() & 0xFFFFFFFFL;
            long rwnd = buf.getInt() & 0xFFFFFFFFL;
            return new Sack(ack, rwnd);
        }
    }

    // ---- 未知 Chunk 类型（静默忽略） ----

    static class UnknownChunk extends SctpChunk {
        final byte[] chunkData;

        UnknownChunk(int type, byte flags, byte[] chunkData) {
            super(type, flags);
            this.chunkData = chunkData;
        }

        @Override
        protected void encodeData(ByteBuffer buf) {
            buf.put(chunkData);
        }
    }
}
