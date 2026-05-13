package com.wenting.mediaserver.protocol.webrtc.core.sctp;

import org.bouncycastle.tls.DTLSTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SCTP over DTLS transport bridge.
 *
 * 将 DTLSTransport 与 SctpAssociation 连接:
 * - 接收线程从 DTLS 读取数据 → 送入 SctpAssociation
 * - SctpAssociation 产生响应包 → 通过 DTLS 发送
 * - 定时重传未确认的 DATA chunk
 */
public class SctpTransport implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SctpTransport.class);

    private static final int SCTP_PORT = 5000;
    private static final long RETRANSMIT_INTERVAL_MS = 500;
    private static final int MAX_RETRANSMIT = 5;

    private final DTLSTransport dtlsTransport;
    private final SctpAssociation association;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    private volatile SctpDataHandler dataHandler;

    public interface SctpDataHandler {
        void onData(int streamId, long ppid, byte[] data, boolean unordered);
    }

    public SctpTransport(DTLSTransport dtlsTransport, boolean isClient) {
        this.dtlsTransport = dtlsTransport;
        this.association = new SctpAssociation(SCTP_PORT, SCTP_PORT, isClient);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sctp-timer");
            t.setDaemon(true);
            return t;
        });

        // Forward association data events to handler
        association.setDataHandler((streamId, ppid, data, unordered) -> {
            SctpDataHandler h = dataHandler;
            if (h != null) {
                h.onData(streamId, ppid, data, unordered);
            }
        });

        // Start receive loop
        Thread recvThread = new Thread(this::receiveLoop, "sctp-recv");
        recvThread.setDaemon(true);
        recvThread.start();

        // Start retransmit timer
        final ScheduledFuture<?> timer = scheduler.scheduleAtFixedRate(
            this::onTick, RETRANSMIT_INTERVAL_MS, RETRANSMIT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // If client, initiate SCTP association
        if (isClient) {
            try {
                List<byte[]> packets = association.connect();
                for (byte[] pkt : packets) {
                    dtlsTransport.send(pkt, 0, pkt.length);
                }
            } catch (IOException e) {
                LOG.error("Failed to send SCTP INIT", e);
            }
        }
    }

    public void setDataHandler(SctpDataHandler handler) {
        this.dataHandler = handler;
    }

    public SctpAssociation getAssociation() {
        return association;
    }

    /**
     * Send data over SCTP (wraps in DATA chunk + sends via DTLS).
     */
    public void sendData(int streamId, long ppid, byte[] data, boolean unordered) throws IOException {
        byte[] pkt = association.createDataPacket(streamId, ppid, data, unordered);
        if (pkt != null) {
            dtlsTransport.send(pkt, 0, pkt.length);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdown();
    }

    // ---- 内部 ----

    private void receiveLoop() {
        byte[] buf = new byte[65536];
        while (!closed) {
            try {
                int len = dtlsTransport.receive(buf, 0, buf.length, 100);
                if (len < 0) continue;

                byte[] packet = new byte[len];
                System.arraycopy(buf, 0, packet, 0, len);

                List<byte[]> responses = association.onPacket(packet);
                if (responses != null) {
                    for (byte[] resp : responses) {
                        if (resp != null) {
                            dtlsTransport.send(resp, 0, resp.length);
                        }
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    LOG.error("SCTP receive error", e);
                }
            }
        }
    }

    private void onTick() {
        if (closed) return;
        association.cleanupRetransmits(MAX_RETRANSMIT);
        try {
            List<byte[]> retransmits = association.retransmit();
            for (byte[] pkt : retransmits) {
                dtlsTransport.send(pkt, 0, pkt.length);
            }
        } catch (IOException e) {
            LOG.error("SCTP retransmit error", e);
        }
    }
}
