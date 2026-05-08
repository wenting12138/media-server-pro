package com.wenting.mediaserver.core.codec.rtmp.amf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Amf0ValueDecoder {

    public List<Object> decodeAll(byte[] payload) {
        ByteBuf buffer = Unpooled.wrappedBuffer(payload == null ? new byte[0] : payload);
        List<Object> values = new ArrayList<Object>();
        while (buffer.isReadable()) {
            values.add(decode(buffer));
        }
        return values;
    }

    public Object decode(ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return null;
        }
        short type = buffer.readUnsignedByte();
        if (type == Amf0Type.NUMBER) {
            return Double.valueOf(buffer.readDouble());
        }
        if (type == Amf0Type.BOOLEAN) {
            return Boolean.valueOf(buffer.readUnsignedByte() != 0);
        }
        if (type == Amf0Type.STRING) {
            return readString(buffer);
        }
        if (type == Amf0Type.OBJECT) {
            return readObject(buffer);
        }
        if (type == Amf0Type.NULL) {
            return null;
        }
        if (type == Amf0Type.ECMA_ARRAY) {
            buffer.readInt();
            return readObject(buffer);
        }
        if (type == Amf0Type.STRICT_ARRAY) {
            int length = buffer.readInt();
            List<Object> values = new ArrayList<Object>(length);
            for (int i = 0; i < length; i++) {
                values.add(decode(buffer));
            }
            return values;
        }
        throw new IllegalArgumentException("Unsupported AMF0 type: " + type);
    }

    private Map<String, Object> readObject(ByteBuf buffer) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        while (buffer.readableBytes() >= 3) {
            int keyLength = buffer.getUnsignedShort(buffer.readerIndex());
            if (keyLength == 0 && buffer.getUnsignedByte(buffer.readerIndex() + 2) == Amf0Type.OBJECT_END) {
                buffer.skipBytes(3);
                break;
            }
            String key = readString(buffer);
            values.put(key, decode(buffer));
        }
        return values;
    }

    private String readString(ByteBuf buffer) {
        int length = buffer.readUnsignedShort();
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new String(bytes, CharsetUtil.UTF_8);
    }
}
