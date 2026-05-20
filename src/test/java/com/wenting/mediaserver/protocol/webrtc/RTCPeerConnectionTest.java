package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.api.*;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.DataChannel;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpParser;
import com.wenting.mediaserver.protocol.webrtc.util.WebrtcSdpUtil;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * RTCPeerConnection API tests.
 */
public class RTCPeerConnectionTest {

    @Test
    public void testCreateOfferContainsRequiredAttributes() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            RTCSessionDescription offer = pc.createOffer().get();
            assertEquals("offer", offer.getType());

            String sdp = offer.getSdp();
            assertTrue("Missing ice-ufrag", sdp.contains("a=ice-ufrag:"));
            assertTrue("Missing ice-pwd", sdp.contains("a=ice-pwd:"));
            assertTrue("Missing fingerprint", sdp.contains("a=fingerprint:sha-256 "));
            assertTrue("Missing candidate", sdp.contains("a=candidate:"));
            assertTrue("Missing media line", sdp.contains("m=application"));
            assertTrue("Missing DTLS/SCTP", sdp.contains("DTLS/SCTP"));
            assertTrue("Missing DTLS setup role", sdp.contains("a=setup:actpass"));
        } finally {
            pc.close();
        }
    }

    @Test
    public void testCreateAnswerContainsRequiredAttributes() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            RTCSessionDescription offer = pc.createOffer().get();
            pc.setRemoteDescription(offer);
            RTCSessionDescription answer = pc.createAnswer().get();
            assertEquals("answer", answer.getType());
            assertTrue(answer.getSdp().contains("a=ice-ufrag:"));
            assertTrue(answer.getSdp().contains("a=fingerprint:sha-256 "));
            assertTrue(answer.getSdp().contains("a=setup:passive"));
        } finally {
            pc.close();
        }
    }

    @Test
    public void testSetLocalRemoteDescription() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            RTCSessionDescription offer = pc.createOffer().get();
            pc.setLocalDescription(offer);
            assertEquals(RTCPeerConnection.SignalingState.HAVE_LOCAL_OFFER, pc.getSignalingState());

            pc.setRemoteDescription(offer);
            assertEquals(RTCPeerConnection.SignalingState.HAVE_REMOTE_OFFER, pc.getSignalingState());
        } finally {
            pc.close();
        }
    }

    @Test
    public void testCreateDataChannel() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            DataChannel dc = pc.createDataChannel("test-channel");
            assertNotNull(dc);
            assertEquals("test-channel", dc.getLabel());
            assertEquals(DataChannel.State.CONNECTING, dc.getState());
        } finally {
            pc.close();
        }
    }

    @Test
    public void testConnectionStateSupportsMultipleListeners() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            AtomicInteger listenerCalls = new AtomicInteger(0);
            AtomicReference<RTCPeerConnection.ConnectionState> first = new AtomicReference<>();
            AtomicReference<RTCPeerConnection.ConnectionState> second = new AtomicReference<>();

            RTCPeerConnection.ListenerSubscription firstSubscription =
                    pc.addConnectionStateListener(state -> {
                        first.set(state);
                        listenerCalls.incrementAndGet();
                    });
            pc.addConnectionStateListener(state -> {
                second.set(state);
                listenerCalls.incrementAndGet();
            });

            emitConnectionState(pc, RTCPeerConnection.ConnectionState.CONNECTED);

            assertEquals(RTCPeerConnection.ConnectionState.CONNECTED, first.get());
            assertEquals(RTCPeerConnection.ConnectionState.CONNECTED, second.get());
            assertEquals(2, listenerCalls.get());

            firstSubscription.close();
            emitConnectionState(pc, RTCPeerConnection.ConnectionState.FAILED);

            assertEquals(RTCPeerConnection.ConnectionState.CONNECTED, first.get());
            assertEquals(RTCPeerConnection.ConnectionState.FAILED, second.get());
            assertEquals(3, listenerCalls.get());
        } finally {
            pc.close();
        }
    }

    @Test
    public void testParseCandidateFromSdp() {
        String sdpAttr = "a=candidate:1 1 UDP 2130706431 127.0.0.1 54321 typ host";
        IceCandidate candidate = WebrtcSdpUtil.parseCandidate(sdpAttr, "ufrag");
        assertNotNull(candidate);
        assertEquals("1", candidate.getFoundation());
        assertEquals(1, candidate.getComponentId());
        assertEquals("127.0.0.1", candidate.getAddress().getHostString());
        assertEquals(54321, candidate.getAddress().getPort());
    }

    @Test
    public void testParseCandidateWithoutPrefix() {
        String candidateStr = "candidate:1 1 UDP 2130706431 127.0.0.1 12345 typ host";
        IceCandidate candidate = WebrtcSdpUtil.parseCandidate(candidateStr, "ufrag");
        assertNotNull(candidate);
        assertEquals(12345, candidate.getAddress().getPort());
    }

    @Test
    public void testRtcSessionDescriptionBasic() {
        RTCSessionDescription desc = new RTCSessionDescription("offer", "sdp content");
        assertEquals("offer", desc.getType());
        assertEquals("sdp content", desc.getSdp());
    }

    @Test
    public void testRtcIceCandidateBasic() {
        RTCIceCandidate c = new RTCIceCandidate("candidate:1 ...", "0", 0);
        assertEquals("candidate:1 ...", c.getCandidate());
        assertEquals("0", c.getSdpMid());
        assertEquals(0, c.getSdpMLineIndex());
    }

    @Test
    public void testSdpOfferParsable() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            RTCSessionDescription offer = pc.createOffer().get();
            // Should be parseable by SdpParser
            SdpDescription parsed = SdpParser.parse(offer.getSdp());
            assertNotNull(parsed.getIceUfrag());
            assertNotNull(parsed.getIcePwd());
        } finally {
            pc.close();
        }
    }

    @Test
    public void testCreateOfferAfterCloseFails() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        pc.close();
        try {
            pc.createOffer().get();
            fail("Expected exception after close");
        } catch (Exception e) {
            // Expected: transport is closed
        }
    }

    // ---- Full Loopback Integration Test ----

    @Test(timeout = 30000)
    public void testLoopbackIceConnection() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            List<RTCIceCandidate> candidates1 = new ArrayList<>();
            List<RTCIceCandidate> candidates2 = new ArrayList<>();
            CountDownLatch pc1Connected = new CountDownLatch(1);
            CountDownLatch pc2Connected = new CountDownLatch(1);

            pc1.addIceCandidateListener(c -> candidates1.add(c));
            pc2.addIceCandidateListener(c -> candidates2.add(c));
            pc1.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED) pc1Connected.countDown();
            });
            pc2.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED) pc2Connected.countDown();
            });

            // Create offer
            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            assertFalse("PC1 should have at least 1 candidate", candidates1.isEmpty());

            // Set offer on PC2
            pc2.setRemoteDescription(offer);

            // Create answer
            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            assertFalse("PC2 should have at least 1 candidate", candidates2.isEmpty());

            // Set answer on PC1
            pc1.setRemoteDescription(answer);

            // Exchange candidates - add remote candidates to start ICE checks
            for (RTCIceCandidate c : candidates1) {
                pc2.addIceCandidate(c);
            }
            for (RTCIceCandidate c : candidates2) {
                pc1.addIceCandidate(c);
            }

            // Wait for ICE connection (up to 10 seconds)
            assertTrue("PC1 ICE not connected",
                pc1Connected.await(10, TimeUnit.SECONDS));
            assertTrue("PC2 ICE not connected",
                pc2Connected.await(5, TimeUnit.SECONDS));

        } finally {
            pc1.close();
            pc2.close();
        }
    }

    @Test(timeout = 60000)
    public void testFullLoopbackDataChannel() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            List<RTCIceCandidate> candidates1 = new ArrayList<>();
            List<RTCIceCandidate> candidates2 = new ArrayList<>();
            CountDownLatch pc1Ice = new CountDownLatch(1);
            CountDownLatch pc2Ice = new CountDownLatch(1);
            CountDownLatch pc1Ready = new CountDownLatch(1);
            CountDownLatch pc2Ready = new CountDownLatch(1);
            CountDownLatch dcReceived = new CountDownLatch(1);
            CountDownLatch msgReceived = new CountDownLatch(1);
            AtomicReference<String> receivedMsg = new AtomicReference<>();

            pc1.addIceCandidateListener(c -> candidates1.add(c));
            pc2.addIceCandidateListener(c -> candidates2.add(c));
            pc1.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED) pc1Ice.countDown();
            });
            pc2.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED) pc2Ice.countDown();
            });
            pc1.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc1Ready.countDown();
            });
            pc2.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc2Ready.countDown();
            });

            // SDP offer/answer exchange
            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            pc2.setRemoteDescription(offer);
            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            pc1.setRemoteDescription(answer);

            // Exchange ICE candidates
            for (RTCIceCandidate c : candidates1) pc2.addIceCandidate(c);
            for (RTCIceCandidate c : candidates2) pc1.addIceCandidate(c);

            // Wait for ICE
            assertTrue("ICE timeout",
                pc1Ice.await(10, TimeUnit.SECONDS));
            assertTrue("ICE timeout",
                pc2Ice.await(5, TimeUnit.SECONDS));

            // Wait for DTLS + SCTP
            assertTrue("PC1 full connection timeout",
                pc1Ready.await(20, TimeUnit.SECONDS));
            assertTrue("PC2 full connection timeout",
                pc2Ready.await(10, TimeUnit.SECONDS));

            // Set up DataChannel handler on PC2 before creating DC on PC1
            pc2.addDataChannelListener(dc -> {
                dcReceived.countDown();
                dc.setMessageHandler((data, isBinary) -> {
                    receivedMsg.set(new String(data, StandardCharsets.UTF_8));
                    msgReceived.countDown();
                });
            });

            // Create DataChannel on PC1 and send message
            DataChannel dc = pc1.createDataChannel("test");
            assertTrue("PC2 should receive DataChannel",
                dcReceived.await(5, TimeUnit.SECONDS));

            dc.send("Hello WebRTC!");
            assertTrue("PC2 should receive message",
                msgReceived.await(5, TimeUnit.SECONDS));
            assertEquals("Hello WebRTC!", receivedMsg.get());

        } finally {
            pc1.close();
            pc2.close();
        }
    }

    @Test(timeout = 60000)
    public void testFullLoopbackDataChannelWithCandidatesImportedFromSdp() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            CountDownLatch pc1Ice = new CountDownLatch(1);
            CountDownLatch pc2Ice = new CountDownLatch(1);
            CountDownLatch pc1Ready = new CountDownLatch(1);
            CountDownLatch pc2Ready = new CountDownLatch(1);
            CountDownLatch dcReceived = new CountDownLatch(1);
            CountDownLatch msgReceived = new CountDownLatch(1);
            AtomicReference<String> receivedMsg = new AtomicReference<>();

            pc1.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED
                    || s == RTCPeerConnection.IceConnectionState.COMPLETED) {
                    pc1Ice.countDown();
                }
            });
            pc2.addIceConnectionStateListener(s -> {
                if (s == RTCPeerConnection.IceConnectionState.CONNECTED
                    || s == RTCPeerConnection.IceConnectionState.COMPLETED) {
                    pc2Ice.countDown();
                }
            });
            pc1.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc1Ready.countDown();
            });
            pc2.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc2Ready.countDown();
            });

            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            pc2.setRemoteDescription(offer);

            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            pc1.setRemoteDescription(answer);

            assertTrue("PC1 ICE timeout", pc1Ice.await(10, TimeUnit.SECONDS));
            assertTrue("PC2 ICE timeout", pc2Ice.await(10, TimeUnit.SECONDS));
            assertTrue("PC1 full connection timeout", pc1Ready.await(20, TimeUnit.SECONDS));
            assertTrue("PC2 full connection timeout", pc2Ready.await(20, TimeUnit.SECONDS));

            pc2.addDataChannelListener(dc -> {
                dcReceived.countDown();
                dc.setMessageHandler((data, isBinary) -> {
                    receivedMsg.set(new String(data, StandardCharsets.UTF_8));
                    msgReceived.countDown();
                });
            });

            DataChannel dc = pc1.createDataChannel("sdp-import");
            assertTrue("PC2 should receive DataChannel", dcReceived.await(5, TimeUnit.SECONDS));

            dc.send("Hello SDP ICE!");
            assertTrue("PC2 should receive message", msgReceived.await(5, TimeUnit.SECONDS));
            assertEquals("Hello SDP ICE!", receivedMsg.get());
        } finally {
            pc1.close();
            pc2.close();
        }
    }

    private static void emitConnectionState(RTCPeerConnection peerConnection,
                                            RTCPeerConnection.ConnectionState state) throws Exception {
        Method method = RTCPeerConnection.class.getDeclaredMethod("setConnectionState",
                RTCPeerConnection.ConnectionState.class);
        method.setAccessible(true);
        method.invoke(peerConnection, state);
    }
}
