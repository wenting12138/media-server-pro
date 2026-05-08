package com.wenting.mediaserver.core.codec.rtmp;

import com.wenting.mediaserver.core.codec.rtmp.amf.Amf0ValueEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RtmpCommandMessage extends RtmpMessage {

    private static final Amf0ValueEncoder AMF0_ENCODER = new Amf0ValueEncoder();

    private final String commandName;
    private final double transactionId;
    private final Object commandObject;
    private final List<Object> arguments;

    public RtmpCommandMessage(
            int chunkStreamId,
            long timestamp,
            int messageStreamId,
            String commandName,
            double transactionId,
            Object commandObject,
            List<Object> arguments
    ) {
        super(
                chunkStreamId,
                timestamp,
                messageStreamId,
                RtmpMessageType.COMMAND_AMF0,
                AMF0_ENCODER.encodeAll(buildValues(commandName, transactionId, commandObject, arguments))
        );
        this.commandName = commandName == null ? "" : commandName;
        this.transactionId = transactionId;
        this.commandObject = commandObject;
        this.arguments = arguments == null
                ? Collections.<Object>emptyList()
                : Collections.unmodifiableList(new ArrayList<Object>(arguments));
    }

    public RtmpCommandMessage(
            int chunkStreamId,
            long timestamp,
            int messageStreamId,
            byte[] payload,
            String commandName,
            double transactionId,
            Object commandObject,
            List<Object> arguments
    ) {
        super(chunkStreamId, timestamp, messageStreamId, RtmpMessageType.COMMAND_AMF0, payload);
        this.commandName = commandName == null ? "" : commandName;
        this.transactionId = transactionId;
        this.commandObject = commandObject;
        this.arguments = arguments == null
                ? Collections.<Object>emptyList()
                : Collections.unmodifiableList(new ArrayList<Object>(arguments));
    }

    public String commandName() {
        return commandName;
    }

    public double transactionId() {
        return transactionId;
    }

    public Object commandObject() {
        return commandObject;
    }

    public List<Object> arguments() {
        return arguments;
    }

    private static List<Object> buildValues(String commandName, double transactionId, Object commandObject, List<Object> arguments) {
        List<Object> values = new ArrayList<Object>();
        values.add(commandName);
        values.add(Double.valueOf(transactionId));
        values.add(commandObject);
        if (arguments != null) {
            values.addAll(arguments);
        }
        return values;
    }
}
