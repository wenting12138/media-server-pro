package com.wenting.mediaserver.core.codec.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.amf.Amf0ValueEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtmpDataMessage extends RtmpMessage {

    private static final Amf0ValueEncoder AMF0_ENCODER = new Amf0ValueEncoder();

    private final List<Object> values;

    public RtmpDataMessage(int chunkStreamId, long timestamp, int messageStreamId, List<Object> values) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.DATA_AMF0, AMF0_ENCODER.encodeAll(values));
        this.values = values == null
                ? Collections.<Object>emptyList()
                : Collections.unmodifiableList(new ArrayList<Object>(values));
    }

    public RtmpDataMessage(int chunkStreamId, long timestamp, int messageStreamId, byte[] payload, List<Object> values) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.DATA_AMF0, payload);
        this.values = values == null
                ? Collections.<Object>emptyList()
                : Collections.unmodifiableList(new ArrayList<Object>(values));
    }

    public List<Object> values() {
        return values;
    }
}
