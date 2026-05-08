package com.wenting.mediaserver.core.codec.rtmp.amf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;

import java.util.List;
import java.util.Map;

public final class Amf0ValueEncoder {

    public byte[] encodeAll(List<Object> values) {
        ByteBuf buffer = Unpooled.buffer();
        if (values != null) {
            for (Object value : values) {
                writeValue(buffer, value);
            }
        }
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public void writeValue(ByteBuf buffer, Object value) {
        if (value == null) {
            buffer.writeByte(Amf0Type.NULL);
            return;
        }
        if (value instanceof String) {
            buffer.writeByte(Amf0Type.STRING);
            writeString(buffer, (String) value);
            return;
        }
        if (value instanceof Number) {
            buffer.writeByte(Amf0Type.NUMBER);
            buffer.writeDouble(((Number) value).doubleValue());
            return;
        }
        if (value instanceof Boolean) {
            buffer.writeByte(Amf0Type.BOOLEAN);
            buffer.writeByte(Boolean.TRUE.equals(value) ? 1 : 0);
            return;
        }
        if (value instanceof Map) {
            buffer.writeByte(Amf0Type.OBJECT);
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) value;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                writeString(buffer, entry.getKey());
                writeValue(buffer, entry.getValue());
            }
            buffer.writeShort(0);
            buffer.writeByte(Amf0Type.OBJECT_END);
            return;
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            buffer.writeByte(Amf0Type.STRICT_ARRAY);
            buffer.writeInt(list.size());
            for (Object item : list) {
                writeValue(buffer, item);
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported AMF0 value type: " + value.getClass().getName());
    }

    private void writeString(ByteBuf buffer, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(CharsetUtil.UTF_8);
        buffer.writeShort(bytes.length);
        buffer.writeBytes(bytes);
    }
}
