package com.wenting.mediaserver.protocol.webrtc.core.sctp;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;
import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpChunk.*;

/**
 * SCTP association state machine (RFC 4960 — simplified for DataChannel).
 *
 * 管理 SCTP 关联的完整生命周期:
 * 1. 四步握手 (INIT → INIT-ACK → COOKIE-ECHO → COOKIE-ACK)
 * 2. DATA chunk 发送/接收 + SACK 确认
 * 3. 基本重传 (超时重发)
 * 4. Verification Tag 和 TSN 管理
 */
public class SctpAssociation {

    public enum State { CLOSED, COOKIE_WAIT, COOKIE_ECHOED, ESTABLISHED }

    // ---- 配置 ----
    private final int localPort;
    private final int peerPort;
    private final boolean isClient;

    // ---- 状态 ----
    private volatile State state = State.CLOSED;

    // ---- 标签 ----
    private final long initiateTag;   // 本端 initiate tag
    private long peerTag;             // 对端 initiate tag

    // ---- TSN ----
    private final AtomicLong nextTsn = new AtomicLong(new Random().nextInt(Integer.MAX_VALUE));
    private long cumulativeTsnAck;
    private long nextExpectedTsn;

    // ---- 流 ----
    private final int os;   // outbound streams
    private final int miss; // inbound streams

    // ---- 未确认数据 ----
    private final Map<Long, SentData> unacknowledged = new LinkedHashMap<>();
    private long lastRwnd = DEFAULT_ADVERTISED_RWND;

    // ---- 事件 ----
    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;

    // ---- 握手状态 ----
    private byte[] pendingCookie;

    public SctpAssociation(int localPort, int peerPort, boolean isClient) {
        this(localPort, peerPort, isClient, DEFAULT_OS, DEFAULT_MISS);
    }

    public SctpAssociation(int localPort, int peerPort, boolean isClient, int os, int miss) {
        this.localPort = localPort;
        this.peerPort = peerPort;
        this.isClient = isClient;
        this.os = os;
        this.miss = miss;
        this.initiateTag = new Random().nextInt() & 0xFFFFFFFFL;
        this.cumulativeTsnAck = 0;
        this.nextExpectedTsn = 0;
    }

    // ---- 事件回调 ----

    public void setDataHandler(DataHandler handler) { this.dataHandler = handler; }
    public void setStateHandler(StateHandler handler) { this.stateHandler = handler; }

    public interface DataHandler {
        void onData(int streamId, long ppid, byte[] data, boolean unordered);
    }

    public interface StateHandler {
        void onStateChange(State newState);
    }

    // ---- 公共 API ----

    public State getState() { return state; }

    /**
     * 客户端发起连接: 生成 INIT 包。
     */
    public List<byte[]> connect() {
        if (!isClient || state != State.CLOSED) return Collections.emptyList();

        long tsn = nextTsn.get();
        Init init = new Init(initiateTag, lastRwnd, os, miss, tsn);
        setState(State.COOKIE_WAIT);
        return Collections.singletonList(packet(init, 0));
    }

    /**
     * 处理接收到的 SCTP 包，返回需要发送的包列表。
     */
    public List<byte[]> onPacket(byte[] data) {
        SctpPacket pkt;
        try {
            pkt = SctpPacket.decode(data);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<byte[]> responses = new ArrayList<>();

        for (SctpChunk chunk : pkt.getChunks()) {
            List<byte[]> r = handleChunk(chunk);
            if (r != null) responses.addAll(r);
        }
        return responses;
    }

    /**
     * 创建包含 DATA chunk 的 SCTP 包。
     */
    public byte[] createDataPacket(int streamId, long ppid, byte[] userData, boolean unordered) {
        long tsn = nextTsn.getAndIncrement();
        int streamSeq = 0;
        Data data = Data.create(tsn, streamId, streamSeq, ppid, userData, unordered || true);

        // Track for retransmission
        synchronized (unacknowledged) {
            unacknowledged.put(tsn, new SentData(data, System.currentTimeMillis()));
        }

        return packet(data, peerTag);
    }

    /**
     * 超时重传: 重新发送所有未确认的 DATA。
     */
    public List<byte[]> retransmit() {
        List<byte[]> packets = new ArrayList<>();
        synchronized (unacknowledged) {
            for (SentData sd : unacknowledged.values()) {
                packets.add(packet(sd.chunk, peerTag));
            }
        }
        return packets;
    }

    /**
     * 清理超时未确认的数据（超过 maxRetrans 次重传则丢弃）。
     */
    public void cleanupRetransmits(int maxRetrans) {
        long now = System.currentTimeMillis();
        synchronized (unacknowledged) {
            Iterator<Map.Entry<Long, SentData>> it = unacknowledged.entrySet().iterator();
            while (it.hasNext()) {
                SentData sd = it.next().getValue();
                if (sd.retransmitCount >= maxRetrans) {
                    it.remove();
                }
            }
        }
    }

    // ---- 内部 ----

    private void setState(State newState) {
        State old = state;
        state = newState;
        if (stateHandler != null && old != newState) {
            stateHandler.onStateChange(newState);
        }
    }

    private List<byte[]> handleChunk(SctpChunk chunk) {
        switch (chunk.getType()) {
            case INIT:      return handleInit((SctpChunk.Init) chunk);
            case INIT_ACK:  return handleInitAck((SctpChunk.InitAck) chunk);
            case COOKIE_ECHO: return handleCookieEcho((SctpChunk.CookieEcho) chunk);
            case COOKIE_ACK:  return handleCookieAck();
            case DATA:      return handleData((SctpChunk.Data) chunk);
            case SACK:      return handleSack((SctpChunk.Sack) chunk);
            default:        return null;
        }
    }

    // 服务端收到 INIT
    private List<byte[]> handleInit(SctpChunk.Init init) {
        peerTag = init.initiateTag;

        long tsn = nextTsn.get();
        pendingCookie = ("cookie:" + initiateTag + ":" + peerTag + ":" + System.currentTimeMillis()).getBytes();
        InitAck ack = new InitAck(initiateTag, lastRwnd, os, miss, tsn, pendingCookie);

        setState(State.COOKIE_ECHOED);
        return Collections.singletonList(packet(ack, peerTag));
    }

    // 客户端收到 INIT-ACK
    private List<byte[]> handleInitAck(SctpChunk.InitAck ack) {
        peerTag = ack.initiateTag;
        cumulativeTsnAck = 0;
        nextExpectedTsn = ack.initialTsn;

        CookieEcho echo = new CookieEcho(ack.stateCookie);
        setState(State.COOKIE_ECHOED);
        return Collections.singletonList(packet(echo, peerTag));
    }

    // 服务端收到 COOKIE-ECHO
    private List<byte[]> handleCookieEcho(SctpChunk.CookieEcho echo) {
        setState(State.ESTABLISHED);
        return Collections.singletonList(packet(new CookieAck(), peerTag));
    }

    // 客户端收到 COOKIE-ACK
    private List<byte[]> handleCookieAck() {
        setState(State.ESTABLISHED);
        return null;
    }

    // 收到 DATA
    private List<byte[]> handleData(SctpChunk.Data data) {
        // Update expected TSN
        if (data.tsn >= nextExpectedTsn) {
            nextExpectedTsn = data.tsn + 1;
            cumulativeTsnAck = data.tsn;
        }

        // Notify data handler
        if (dataHandler != null) {
            dataHandler.onData(data.streamId, data.ppid, data.userData, data.unordered);
        }

        // Send SACK
        List<byte[]> responses = new ArrayList<>();
        responses.add(packet(new Sack(cumulativeTsnAck, lastRwnd), peerTag));
        return responses;
    }

    // 收到 SACK
    private List<byte[]> handleSack(SctpChunk.Sack sack) {
        synchronized (unacknowledged) {
            // Remove acknowledged TSNs
            long cumAck = sack.cumulativeTsnAck;
            Iterator<Map.Entry<Long, SentData>> it = unacknowledged.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, SentData> entry = it.next();
                if (entry.getKey() == cumAck || entry.getKey() < cumAck) {
                    it.remove();
                }
            }
        }
        return null;
    }

    // ---- 包构造 ----

    private byte[] packet(SctpChunk chunk, long tag) {
        List<SctpChunk> chunks = Collections.singletonList(chunk);
        SctpPacket pkt = new SctpPacket(localPort, peerPort, tag, chunks);
        return pkt.encode();
    }

    // ---- 内部类 ----

    private static class SentData {
        final SctpChunk.Data chunk;
        final long sentTime;
        int retransmitCount = 0;

        SentData(SctpChunk.Data chunk, long sentTime) {
            this.chunk = chunk;
            this.sentTime = sentTime;
        }
    }
}
