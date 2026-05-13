package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.api.*;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.DataChannel;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SignalingMessage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * 完整的 WebRTC 信令交换示例 — 展示 offer/answer + ICE candidate 交换。
 *
 * 信令通道用 LinkedBlockingQueue 模拟（JSON 消息），实际部署可替换为 WebSocket/HTTP。
 *
 * 流程：
 *  1. 设置 ICE candidate / ontrack / onDataChannel 回调
 *  2. PC1 createOffer, setLocalDescription → 信令 → PC2 setRemoteDescription
 *  3. PC2 createAnswer, setLocalDescription → 信令 → PC1 setRemoteDescription
 *  4. 双方通过信令交换 ICE candidate
 *  5. ICE → DTLS → SCTP 自动连接
 *  6. 验证 DataChannel 双向消息 + 媒体轨道可达
 */
public class SignalingDemo {

    private static final Logger LOG = Logger.getLogger(SignalingDemo.class.getName());

    // ============================================================
    // 模拟信令消息
    // ============================================================

    static class SMsg {
        final String type, from, to, data;
        SMsg(String type, String from, String to, String data) {
            this.type = type; this.from = from; this.to = to; this.data = data;
        }
        String toJson() {
            return SignalingMessage.valueOf(type.toUpperCase()).toJson(data, from, to);
        }
        static SMsg fromJson(String json) {
            return new SMsg(extract(json, "type"), extract(json, "from"),
                extract(json, "to"), extract(json, "data"));
        }
        private static String extract(String json, String key) {
            String q = "\"" + key + "\":\"";
            int s = json.indexOf(q);
            if (s < 0) return "";
            s += q.length();
            int e = json.indexOf("\"", s);
            return e < 0 ? "" : json.substring(s, e)
                .replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    // ============================================================
    // 模拟信令通道（JSON 消息队列）
    // ============================================================

    static class SignalingChannel {
        final String name;
        final LinkedBlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        SignalingChannel peer;
        SignalingChannel(String name) { this.name = name; }

        void send(String json) {
            LOG.info("[信令] " + name + " → " + peer.name + " [" + SMsg.fromJson(json).type + "]");
            peer.inbox.offer(json);
        }

        SMsg receive(long timeout, TimeUnit unit) throws InterruptedException {
            String json = inbox.poll(timeout, unit);
            if (json == null) return null;
            SMsg m = SMsg.fromJson(json);
            LOG.info("[信令] " + name + " ← " + m.from + " [" + m.type + "]");
            return m;
        }
    }

    // ============================================================
    // 信令方式1: 将 ICE candidate 放入信令通道传输
    // ============================================================

    /** 通过信令通道交换 SDP + ICE candidates，等待连接后验证 DataChannel + 媒体 */
    @Test
    public void testSignalingExchange() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        SignalingChannel ch1 = new SignalingChannel("PC1");
        SignalingChannel ch2 = new SignalingChannel("PC2");
        ch1.peer = ch2;
        ch2.peer = ch1;

        // ---- 同步工具 ----
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch msgReceived = new CountDownLatch(1);
        CountDownLatch audioTrackSeen = new CountDownLatch(1);
        CountDownLatch videoTrackSeen = new CountDownLatch(1);
        AtomicReference<String> rcvdMsg = new AtomicReference<>();

        try {
            // ================================================================
            // Step 0: 注册所有回调（信令开始前，避免丢失事件）
            // ================================================================

            // -- ICE candidate 收集 --
            List<RTCIceCandidate> pc1Cands = new ArrayList<>();
            List<RTCIceCandidate> pc2Cands = new ArrayList<>();
            pc1.onIceCandidate = c -> pc1Cands.add(c);
            pc2.onIceCandidate = c -> pc2Cands.add(c);

            // -- DataChannel --
            final DataChannel dc = pc1.createDataChannel("chat");
            CountDownLatch dcReady = new CountDownLatch(1);
            dc.setStateHandler(s -> {
                if (s == DataChannel.State.OPEN) dcReady.countDown();
            });
            pc2.onDataChannel = remoteDc -> {
                remoteDc.setMessageHandler((data, isBinary) -> {
                    rcvdMsg.set(new String(data, StandardCharsets.UTF_8));
                    msgReceived.countDown();
                });
            };

            // -- Media --
            pc1.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio1"));
            pc1.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video1"));
            pc2.ontrack = receiver -> {
                LOG.info("PC2 ontrack: " + receiver.getTrack().getKind());
                if (receiver.getTrack().getKind() == MediaStreamTrack.Kind.AUDIO)
                    audioTrackSeen.countDown();
                if (receiver.getTrack().getKind() == MediaStreamTrack.Kind.VIDEO)
                    videoTrackSeen.countDown();
            };

            // -- 连接状态 --
            pc2.onConnectionStateChange = s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) connectedLatch.countDown();
            };

            // ================================================================
            // Step 1: PC1 → Offer (信令)
            // ================================================================
            LOG.info("── Step 1: PC1 createOffer ──");
            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            ch1.send(new SMsg("offer", "PC1", "PC2", offer.getSdp()).toJson());

            // ================================================================
            // Step 2: PC2 → Answer (信令)
            // ================================================================
            LOG.info("── Step 2: PC2 createAnswer ──");
            SMsg recvOffer = ch2.receive(5, TimeUnit.SECONDS);
            assertNotNull("PC2 should receive offer", recvOffer);
            pc2.setRemoteDescription(new RTCSessionDescription("offer", recvOffer.data));

            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            ch2.send(new SMsg("answer", "PC2", "PC1", answer.getSdp()).toJson());

            // ================================================================
            // Step 3: PC1 接收 Answer
            // ================================================================
            LOG.info("── Step 3: PC1 receive answer ──");
            SMsg recvAnswer = ch1.receive(5, TimeUnit.SECONDS);
            assertNotNull("PC1 should receive answer", recvAnswer);
            pc1.setRemoteDescription(new RTCSessionDescription("answer", recvAnswer.data));

            // ================================================================
            // Step 4: 通过信令交换 ICE Candidates
            // ================================================================
            LOG.info("── Step 4: Exchange ICE candidates via signaling ──");
            for (RTCIceCandidate c : pc1Cands)
                ch1.send(new SMsg("ice_candidate", "PC1", "PC2", c.getCandidate()).toJson());
            for (RTCIceCandidate c : pc2Cands)
                ch2.send(new SMsg("ice_candidate", "PC2", "PC1", c.getCandidate()).toJson());

            // PC1 的收件箱里有 PC2 的 candidate → 加到 PC1
            SMsg m;
            while ((m = ch1.receive(2, TimeUnit.SECONDS)) != null) {
                if ("ice_candidate".equals(m.type))
                    pc1.addIceCandidate(new RTCIceCandidate(m.data, null, 0));
            }
            // PC2 的收件箱里有 PC1 的 candidate → 加到 PC2
            while ((m = ch2.receive(2, TimeUnit.SECONDS)) != null) {
                if ("ice_candidate".equals(m.type))
                    pc2.addIceCandidate(new RTCIceCandidate(m.data, null, 0));
            }

            // ================================================================
            // Step 5: 等待连接（ICE → DTLS → SCTP 自动完成）
            // ================================================================
            LOG.info("── Step 5: Wait for connection ──");
            assertTrue("Connection timeout (ICE+DTLS+SCTP)",
                connectedLatch.await(30, TimeUnit.SECONDS));
            LOG.info("Connected! ICE+DTLS+SCTP established.");

            // ================================================================
            // Step 6: 验证 DataChannel 消息
            // ================================================================
            LOG.info("── Step 6: DataChannel messaging ──");
            assertTrue("DataChannel should open", dcReady.await(5, TimeUnit.SECONDS));

            String testMsg = "你好 WebRTC!";
            dc.send(testMsg.getBytes(StandardCharsets.UTF_8));
            assertTrue("DataChannel message should be received",
                msgReceived.await(5, TimeUnit.SECONDS));
            assertEquals("Message content should match", testMsg, rcvdMsg.get());
            LOG.info("DataChannel message OK: \"" + rcvdMsg.get() + "\"");

            // ================================================================
            // Step 7: 验证媒体轨道
            // ================================================================
            LOG.info("── Step 7: Verify media tracks ──");
            assertTrue("PC2 should receive audio track",
                audioTrackSeen.await(5, TimeUnit.SECONDS));
            assertTrue("PC2 should receive video track",
                videoTrackSeen.await(5, TimeUnit.SECONDS));
            LOG.info("Both audio+video tracks received by PC2.");

            LOG.info("=== 信令 Demo 完成 ===");

        } finally {
            pc1.close();
            pc2.close();
        }
    }
}
