package com.wenting.mediaserver.core.codec.rtmp.amf;

public final class Amf0Type {

    public static final short NUMBER = 0x00;
    public static final short BOOLEAN = 0x01;
    public static final short STRING = 0x02;
    public static final short OBJECT = 0x03;
    public static final short NULL = 0x05;
    public static final short ECMA_ARRAY = 0x08;
    public static final short OBJECT_END = 0x09;
    public static final short STRICT_ARRAY = 0x0A;

    private Amf0Type() {
    }
}
