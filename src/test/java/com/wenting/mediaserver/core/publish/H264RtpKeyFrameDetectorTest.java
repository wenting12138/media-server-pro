package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.video.H264RtpKeyFrameDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H264RtpKeyFrameDetectorTest {

    private final H264RtpKeyFrameDetector detector = new H264RtpKeyFrameDetector();

    @Test
    void shouldDetectSingleNalIdr() {
        assertTrue(detector.isKeyFrame(packet((byte) 0x65, new byte[]{0x01}), header(12, 2)));
    }

    @Test
    void shouldDetectFuAIdrStartPacket() {
        assertTrue(detector.isKeyFrame(packet((byte) 0x7C, new byte[]{(byte) 0x85, 0x01}), header(12, 3)));
    }

    @Test
    void shouldDetectStapAContainingIdr() {
        assertTrue(detector.isKeyFrame(packet((byte) 0x78, new byte[]{
                0x00, 0x02, 0x67, 0x42,
                0x00, 0x02, 0x65, 0x01
        }), header(12, 9)));
    }

    @Test
    void shouldIgnoreNonKeyFramePackets() {
        assertFalse(detector.isKeyFrame(packet((byte) 0x41, new byte[]{0x01}), header(12, 2)));
        assertFalse(detector.isKeyFrame(packet((byte) 0x7C, new byte[]{0x05, 0x01}), header(12, 3)));
    }

    private static byte[] packet(byte nalHeader, byte[] payload) {
        byte[] packet = new byte[12 + 1 + payload.length];
        packet[12] = nalHeader;
        System.arraycopy(payload, 0, packet, 13, payload.length);
        return packet;
    }

    private static RtpPacketHeader header(int payloadOffset, int payloadLength) {
        return new RtpPacketHeader(2, false, false, 0, false, 96, 1, 1L, 1L, 12, payloadOffset, payloadLength);
    }
}
