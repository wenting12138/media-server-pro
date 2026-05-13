package com.wenting.mediaserver.protocol.webrtc.core.sctp;

import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;

/**
 * WebRTC DataChannel (RFC 8831, RFC 8832).
 *
 * 每个 DataChannel 对应一个 SCTP stream。支持 String 和 Binary 数据传输。
 */
public class DataChannel {
    private static final Logger LOG = LoggerFactory.getLogger(DataChannel.class);

    public enum State { CONNECTING, OPEN, CLOSING, CLOSED }

    private static final AtomicInteger nextId = new AtomicInteger(0);

    private final int id;
    private final String label;
    private final int channelType;
    private final int reliabilityParameter;
    private volatile State state = State.CONNECTING;

    private volatile MessageHandler messageHandler;
    private volatile StateHandler stateHandler;
    private SctpTransport transport;

    public interface MessageHandler {
        void onMessage(byte[] data, boolean isBinary);
    }

    public interface StateHandler {
        void onStateChange(State newState);
    }

    // ---- 本地创建 ----

    public DataChannel(SctpTransport transport, String label, boolean unordered) {
        this(transport, nextId.getAndIncrement(), label,
            unordered ? DATA_CHANNEL_RELIABLE_UNORDERED : DATA_CHANNEL_RELIABLE,
            0);
    }

    public DataChannel(SctpTransport transport, int id, String label, int channelType, int reliabilityParam) {
        this.transport = transport;
        this.id = id;
        this.label = label;
        this.channelType = channelType;
        this.reliabilityParameter = reliabilityParam;
    }

    // ---- 事件回调 ----

    public void setMessageHandler(MessageHandler handler) { this.messageHandler = handler; }
    public void setStateHandler(StateHandler handler) { this.stateHandler = handler; }

    // ---- 属性 ----

    public void setTransport(SctpTransport transport) {
        this.transport = transport;
    }

    public int getId() { return id; }
    public String getLabel() { return label; }
    public State getState() { return state; }
    public boolean isUnordered() {
        return channelType == DATA_CHANNEL_RELIABLE_UNORDERED
            || channelType == DATA_CHANNEL_PARTIAL_RELIABLE_REXMIT
            || channelType == DATA_CHANNEL_PARTIAL_RELIABLE_TIMED;
    }

    // ---- 操作 ----

    /**
     * Deliver received data from the SCTP transport to this channel's message handler.
     */
    public void receiveMessage(byte[] data, boolean isBinary) {
        MessageHandler handler = messageHandler;
        if (handler != null) {
            handler.onMessage(data, isBinary);
        }
    }

    /**
     * 发送 DATA_CHANNEL_OPEN 消息开始建立通道。
     */
    public void open() throws IOException {
        byte[] openMsg = encodeOpenMessage();
        transport.sendData(id, PPID_WEBRTC_STRING, openMsg, isUnordered());
    }

    /**
     * 发送文本消息 (PPID=WebRTC String)。
     */
    public void send(String text) throws IOException {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        transport.sendData(id, PPID_WEBRTC_STRING, data, isUnordered());
    }

    /**
     * 发送二进制消息 (PPID=WebRTC Binary)。
     */
    public void send(byte[] data) throws IOException {
        transport.sendData(id, PPID_WEBRTC_BINARY, data, isUnordered());
    }

    /**
     * 收到 DATA_CHANNEL_OPEN 消息时调用 (来自远端)。
     */
    public void handleOpenMessage(byte[] openData) throws IOException {
        // Send ACK back
        byte[] ack = new byte[]{ (byte) DATA_CHANNEL_ACK };
        transport.sendData(id, PPID_WEBRTC_STRING, ack, isUnordered());

        setState(State.OPEN);
    }

    /**
     * 收到 DATA_CHANNEL_ACK 消息时调用 (来自远端)。
     */
    public void handleAck() {
        setState(State.OPEN);
    }

    /**
     * 通道关闭。
     */
    public void close() {
        setState(State.CLOSED);
    }

    // ---- 内部 ----

    private void setState(State newState) {
        State old = state;
        state = newState;
        if (stateHandler != null && old != newState) {
            stateHandler.onStateChange(newState);
        }
    }

    private byte[] encodeOpenMessage() {
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalLen = 1 + 1 + 2 + 4 + 2 + 2 + labelBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.put((byte) DATA_CHANNEL_OPEN);     // Message Type
        buf.put((byte) channelType);            // Channel Type
        buf.putShort((short) 0);                // Priority (unused)
        buf.putInt(reliabilityParameter);       // Reliability Parameter
        buf.putShort((short) labelBytes.length);// Label Length
        buf.putShort((short) 0);                // Protocol Length (unused)
        buf.put(labelBytes);                    // Label

        return buf.array();
    }

    /**
     * 解析 DATA_CHANNEL_OPEN 消息，返回 DataChannel 实例。
     */
    public static DataChannel parseOpenMessage(SctpTransport transport, byte[] data, int streamId) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int msgType = buf.get() & 0xFF;
        if (msgType != DATA_CHANNEL_OPEN) return null;

        int chanType = buf.get() & 0xFF;
        buf.getShort(); // skip priority
        int reliability = buf.getInt();
        int labelLen = buf.getShort() & 0xFFFF;
        int protoLen = buf.getShort() & 0xFFFF;

        String label = "";
        if (labelLen > 0 && buf.remaining() >= labelLen) {
            byte[] labelBytes = new byte[labelLen];
            buf.get(labelBytes);
            label = new String(labelBytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        DataChannel dc = new DataChannel(transport, streamId, label, chanType, reliability);
        return dc;
    }
}
