package com.wenting.mediaserver.core.codec.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.amf.Amf0ValueDecoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtmpMessageFactory {

    private final Amf0ValueDecoder amf0ValueDecoder = new Amf0ValueDecoder();

    public RtmpMessage create(int chunkStreamId, long timestamp, int messageStreamId, int messageTypeId, byte[] payload) {
        if (messageTypeId == RtmpMessageType.SET_CHUNK_SIZE && payload.length >= 4) {
            return new RtmpSetChunkSizeMessage(chunkStreamId, timestamp, messageStreamId, readInt(payload, 0));
        }
        if (messageTypeId == RtmpMessageType.ABORT && payload.length >= 4) {
            return new RtmpAbortMessage(chunkStreamId, timestamp, messageStreamId, readInt(payload, 0));
        }
        if (messageTypeId == RtmpMessageType.ACKNOWLEDGEMENT && payload.length >= 4) {
            return new RtmpAcknowledgementMessage(chunkStreamId, timestamp, messageStreamId, readInt(payload, 0));
        }
        if (messageTypeId == RtmpMessageType.WINDOW_ACKNOWLEDGEMENT_SIZE && payload.length >= 4) {
            return new RtmpWindowAcknowledgementSizeMessage(chunkStreamId, timestamp, messageStreamId, readInt(payload, 0));
        }
        if (messageTypeId == RtmpMessageType.SET_PEER_BANDWIDTH && payload.length >= 5) {
            return new RtmpSetPeerBandwidthMessage(chunkStreamId, timestamp, messageStreamId, readInt(payload, 0), payload[4] & 0xFF);
        }
        if (messageTypeId == RtmpMessageType.COMMAND_AMF0) {
            return commandMessage(chunkStreamId, timestamp, messageStreamId, payload);
        }
        if (messageTypeId == RtmpMessageType.DATA_AMF0) {
            return new RtmpDataMessage(chunkStreamId, timestamp, messageStreamId, payload, amf0ValueDecoder.decodeAll(payload));
        }
        if (messageTypeId == RtmpMessageType.AUDIO) {
            return new RtmpAudioMessage(chunkStreamId, timestamp, messageStreamId, payload);
        }
        if (messageTypeId == RtmpMessageType.VIDEO) {
            return new RtmpVideoMessage(chunkStreamId, timestamp, messageStreamId, payload);
        }
        return new RtmpUnknownMessage(chunkStreamId, timestamp, messageStreamId, messageTypeId, payload);
    }

    private RtmpMessage commandMessage(int chunkStreamId, long timestamp, int messageStreamId, byte[] payload) {
        List<Object> values = amf0ValueDecoder.decodeAll(payload);
        String commandName = values.isEmpty() || !(values.get(0) instanceof String) ? "" : (String) values.get(0);
        double transactionId = values.size() > 1 && values.get(1) instanceof Number
                ? ((Number) values.get(1)).doubleValue()
                : 0.0d;
        Object commandObject = values.size() > 2 ? values.get(2) : null;
        List<Object> arguments = values.size() > 3
                ? new ArrayList<Object>(values.subList(3, values.size()))
                : Collections.<Object>emptyList();
        return new RtmpCommandMessage(chunkStreamId, timestamp, messageStreamId, payload, commandName, transactionId, commandObject, arguments);
    }

    private int readInt(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 24)
                | ((payload[offset + 1] & 0xFF) << 16)
                | ((payload[offset + 2] & 0xFF) << 8)
                | (payload[offset + 3] & 0xFF);
    }
}
