package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.api.*;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpParser;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Media channel integration tests (SRTP loopback, SDP media negotiation).
 */
public class MediaIntegrationTest {

    @Test
    public void testOfferWithAudioVideo() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        try {
            MediaStreamTrack audio = new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio1");
            MediaStreamTrack video = new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video1");
            pc.addTrack(audio);
            pc.addTrack(video);

            RTCSessionDescription offer = pc.createOffer().get();
            String sdp = offer.getSdp();

            assertTrue("Missing m=audio", sdp.contains("m=audio"));
            assertTrue("Missing m=video", sdp.contains("m=video"));
            assertTrue("Missing opus rtpmap", sdp.contains("rtpmap:111 opus/48000/2"));
            assertTrue("Missing H264 rtpmap", sdp.contains("rtpmap:96 H264/90000"));
            assertTrue("Missing audio ssrc", sdp.contains("a=ssrc:1"));
            assertTrue("Missing video ssrc", sdp.contains("a=ssrc:2"));
            assertTrue("Missing sendrecv", sdp.contains("sendrecv"));
            assertTrue("Missing BUNDLE with 3 mids", sdp.contains("BUNDLE 0 1 2"));
            assertTrue("Missing application media", sdp.contains("m=application"));
            assertTrue("Missing DTLS/SCTP", sdp.contains("DTLS/SCTP"));
            assertTrue("Missing audio 111 PT", sdp.contains("UDP/TLS/RTP/SAVPF 111"));
            assertTrue("Missing video 96 PT", sdp.contains("UDP/TLS/RTP/SAVPF 96"));

            // Verify parseable
            SdpDescription parsed = SdpParser.parse(sdp);
            // app + audio + video = 3 media sections
            assertEquals(3, parsed.getMediaDescriptions().size());

            // Check transceivers
            assertEquals(2, pc.getTransceivers().size());
            assertEquals(MediaStreamTrack.Kind.AUDIO, pc.getTransceivers().get(0).getKind());
            assertEquals(MediaStreamTrack.Kind.VIDEO, pc.getTransceivers().get(1).getKind());
        } finally {
            pc.close();
        }
    }

    @Test
    public void testAnswerParsesMediaSections() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            pc1.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio1"));

            RTCSessionDescription offer = pc1.createOffer().get();
            pc2.setRemoteDescription(offer);

            assertEquals("PC2 should have 1 transceiver from parsing audio",
                1, pc2.getTransceivers().size());
            assertEquals("PC2 transceiver should be AUDIO",
                MediaStreamTrack.Kind.AUDIO, pc2.getTransceivers().get(0).getKind());
            assertEquals("PC2 receiver should know peer SSRC",
                1L, pc2.getTransceivers().get(0).getReceiver().getPeerSsrc());

            RTCSessionDescription answer = pc2.createAnswer().get();
            String answerSdp = answer.getSdp();

            // Answer should have audio media line
            assertTrue("Answer missing m=audio", answerSdp.contains("m=audio"));
            // Direction depends on whether PC2 added tracks; at minimum it should exist
            assertTrue("Answer should have audio PT", answerSdp.contains("UDP/TLS/RTP/SAVPF 111"));
        } finally {
            pc1.close();
            pc2.close();
        }
    }

    @Test(timeout = 60000)
    public void testSrtpLoopbackAudioVideo() throws Exception {
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            // ---- Setup candidate exchange & connection latches ----
            List<RTCIceCandidate> candidates1 = new ArrayList<>();
            List<RTCIceCandidate> candidates2 = new ArrayList<>();
            CountDownLatch pc1Connected = new CountDownLatch(1);
            CountDownLatch pc2Connected = new CountDownLatch(1);
            CountDownLatch audioTrackReceived = new CountDownLatch(1);
            CountDownLatch videoTrackReceived = new CountDownLatch(1);
            CountDownLatch audioPacketReceived = new CountDownLatch(1);
            CountDownLatch videoPacketReceived = new CountDownLatch(1);
            AtomicReference<byte[]> receivedAudioPayload = new AtomicReference<>();
            AtomicReference<byte[]> receivedVideoPayload = new AtomicReference<>();

            pc1.addIceCandidateListener(c -> candidates1.add(c));
            pc2.addIceCandidateListener(c -> candidates2.add(c));
            pc1.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc1Connected.countDown();
            });
            pc2.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) pc2Connected.countDown();
            });

            // ---- Add media tracks to PC1 ----
            MediaStreamTrack audioTrack = new MediaStreamTrack(
                MediaStreamTrack.Kind.AUDIO, "audio1");
            MediaStreamTrack videoTrack = new MediaStreamTrack(
                MediaStreamTrack.Kind.VIDEO, "video1");
            pc1.addTrack(audioTrack);
            pc1.addTrack(videoTrack);

            // ---- Set up ontrack on PC2 ----
            pc2.addTrackListener(receiver -> {
                MediaStreamTrack.Kind kind = receiver.getTrack().getKind();
                if (kind == MediaStreamTrack.Kind.AUDIO) {
                    audioTrackReceived.countDown();
                    receiver.setOnPacket(pkt -> {
                        receivedAudioPayload.set(pkt.getPayload());
                        audioPacketReceived.countDown();
                    });
                } else if (kind == MediaStreamTrack.Kind.VIDEO) {
                    videoTrackReceived.countDown();
                    receiver.setOnPacket(pkt -> {
                        receivedVideoPayload.set(pkt.getPayload());
                        videoPacketReceived.countDown();
                    });
                }
            });

            // ---- SDP offer/answer exchange ----
            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            pc2.setRemoteDescription(offer);

            assertTrue("PC2 should receive audio track notification",
                audioTrackReceived.await(5, TimeUnit.SECONDS));
            assertTrue("PC2 should receive video track notification",
                videoTrackReceived.await(5, TimeUnit.SECONDS));

            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            pc1.setRemoteDescription(answer);

            // ---- Exchange ICE candidates ----
            for (RTCIceCandidate c : candidates1) pc2.addIceCandidate(c);
            for (RTCIceCandidate c : candidates2) pc1.addIceCandidate(c);

            // ---- Wait for full connection (ICE + DTLS + SCTP) ----
            assertTrue("PC1 connection timeout",
                pc1Connected.await(20, TimeUnit.SECONDS));
            assertTrue("PC2 connection timeout",
                pc2Connected.await(20, TimeUnit.SECONDS));

            // ---- SRTP: send encrypted audio/video packets from PC1 to PC2 ----
            RTCRtpTransceiver audioTransceiver = pc1.getTransceivers().get(0);
            RTCRtpTransceiver videoTransceiver = pc1.getTransceivers().get(1);

            // Verify SRTP contexts were created
            assertNotNull("Audio send SRTP context should exist",
                audioTransceiver.getSender().getSrtpContext());
            assertNotNull("Video send SRTP context should exist",
                videoTransceiver.getSender().getSrtpContext());

            long audioSsrc = audioTransceiver.getSender().getSsrc();
            long videoSsrc = videoTransceiver.getSender().getSsrc();

            byte[] audioPayload = "Hello Audio SRTP!".getBytes();
            byte[] videoPayload = "Hello Video SRTP! VideoData".getBytes();

            // Audio packet
            RtpPacket audioPacket = new RtpPacket(2, false, false, 0, false,
                111, 1, 1000L, audioSsrc, null, audioPayload);
            SrtpTransform audioTransform = new SrtpTransform(
                audioTransceiver.getSender().getSrtpContext(), audioSsrc);
            byte[] protectedAudio = audioTransform.protect(audioPacket);
            pc1.sendSrtpPacket(protectedAudio);

            // Video packet
            RtpPacket videoPacket = new RtpPacket(2, false, false, 0, false,
                96, 1, 2000L, videoSsrc, null, videoPayload);
            SrtpTransform videoTransform = new SrtpTransform(
                videoTransceiver.getSender().getSrtpContext(), videoSsrc);
            byte[] protectedVideo = videoTransform.protect(videoPacket);
            pc1.sendSrtpPacket(protectedVideo);

            // ---- Wait for delivery ----
            assertTrue("Audio packet not received on PC2",
                audioPacketReceived.await(5, TimeUnit.SECONDS));
            assertTrue("Video packet not received on PC2",
                videoPacketReceived.await(5, TimeUnit.SECONDS));

            assertArrayEquals("Audio payload mismatch",
                audioPayload, receivedAudioPayload.get());
            assertArrayEquals("Video payload mismatch",
                videoPayload, receivedVideoPayload.get());

        } finally {
            pc1.close();
            pc2.close();
        }
    }

    @Test(timeout = 30000)
    public void testSrtpBidirectionalMedia() throws Exception {
        // Both sides add tracks, send media in both directions
        RTCPeerConnection pc1 = new RTCPeerConnection();
        RTCPeerConnection pc2 = new RTCPeerConnection();

        try {
            List<RTCIceCandidate> candidates1 = new ArrayList<>();
            List<RTCIceCandidate> candidates2 = new ArrayList<>();
            CountDownLatch bothConnected = new CountDownLatch(2);
            CountDownLatch pc2ReceivedAudio = new CountDownLatch(1);
            CountDownLatch pc1ReceivedVideo = new CountDownLatch(1);
            AtomicReference<byte[]> pc2AudioPayload = new AtomicReference<>();
            AtomicReference<byte[]> pc1VideoPayload = new AtomicReference<>();

            pc1.addIceCandidateListener(c -> candidates1.add(c));
            pc2.addIceCandidateListener(c -> candidates2.add(c));
            pc1.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) bothConnected.countDown();
            });
            pc2.addConnectionStateListener(s -> {
                if (s == RTCPeerConnection.ConnectionState.CONNECTED) bothConnected.countDown();
            });

            // PC1 sends audio, PC2 sends video
            pc1.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "pc1-audio"));
            pc2.addTrackListener(receiver -> {
                if (receiver.getTrack().getKind() == MediaStreamTrack.Kind.AUDIO) {
                    receiver.setOnPacket(pkt -> {
                        pc2AudioPayload.set(pkt.getPayload());
                        pc2ReceivedAudio.countDown();
                    });
                }
            });

            RTCSessionDescription offer = pc1.createOffer().get();
            pc1.setLocalDescription(offer);
            pc2.setRemoteDescription(offer);

            // PC2 adds video track (now it has transceivers from parsing offer)
            pc2.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "pc2-video"));

            pc1.addTrackListener(receiver -> {
                if (receiver.getTrack().getKind() == MediaStreamTrack.Kind.VIDEO) {
                    receiver.setOnPacket(pkt -> {
                        pc1VideoPayload.set(pkt.getPayload());
                        pc1ReceivedVideo.countDown();
                    });
                }
            });

            RTCSessionDescription answer = pc2.createAnswer().get();
            pc2.setLocalDescription(answer);
            pc1.setRemoteDescription(answer);

            for (RTCIceCandidate c : candidates1) pc2.addIceCandidate(c);
            for (RTCIceCandidate c : candidates2) pc1.addIceCandidate(c);

            assertTrue("Both sides should connect",
                bothConnected.await(20, TimeUnit.SECONDS));

            // Find PC1's audio sender and PC2's video sender
            RTCRtpTransceiver pc1Audio = null;
            RTCRtpTransceiver pc2Video = null;
            for (RTCRtpTransceiver t : pc1.getTransceivers()) {
                if (t.getKind() == MediaStreamTrack.Kind.AUDIO) pc1Audio = t;
            }
            for (RTCRtpTransceiver t : pc2.getTransceivers()) {
                if (t.getKind() == MediaStreamTrack.Kind.VIDEO) pc2Video = t;
            }
            assertNotNull("PC1 should have audio transceiver", pc1Audio);
            assertNotNull("PC2 should have video transceiver", pc2Video);
            assertNotNull("PC1 audio SRTP context", pc1Audio.getSender().getSrtpContext());
            assertNotNull("PC2 video SRTP context", pc2Video.getSender().getSrtpContext());

            // PC1 sends audio packet
            byte[] audioData = "audio-from-pc1".getBytes();
            RtpPacket audioPkt = new RtpPacket(2, false, false, 0, false,
                111, 1, 1000L, pc1Audio.getSender().getSsrc(), null, audioData);
            SrtpTransform audioTx = new SrtpTransform(
                pc1Audio.getSender().getSrtpContext(), pc1Audio.getSender().getSsrc());
            pc1.sendSrtpPacket(audioTx.protect(audioPkt));

            assertTrue("PC2 should receive audio",
                pc2ReceivedAudio.await(5, TimeUnit.SECONDS));
            assertArrayEquals("PC2 audio payload mismatch",
                audioData, pc2AudioPayload.get());

            // PC2 sends video packet
            byte[] videoData = "video-from-pc2".getBytes();
            RtpPacket videoPkt = new RtpPacket(2, false, false, 0, false,
                96, 1, 2000L, pc2Video.getSender().getSsrc(), null, videoData);
            SrtpTransform videoTx = new SrtpTransform(
                pc2Video.getSender().getSrtpContext(), pc2Video.getSender().getSsrc());
            pc2.sendSrtpPacket(videoTx.protect(videoPkt));

            assertTrue("PC1 should receive video",
                pc1ReceivedVideo.await(5, TimeUnit.SECONDS));
            assertArrayEquals("PC1 video payload mismatch",
                videoData, pc1VideoPayload.get());

        } finally {
            pc1.close();
            pc2.close();
        }
    }
}
