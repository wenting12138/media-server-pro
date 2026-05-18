package com.wenting.mediaserver.core.transcode.canonical;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

final class RtspRtpAudioCanonicalizerTest {

    @Test
    void extractsFullAacAccessUnitFromRtpPayload() {
        RtspRtpAudioCanonicalizer canonicalizer = new RtspRtpAudioCanonicalizer();
        ITrack track = new ITrack() {
            @Override
            public String trackId() {
                return "audio";
            }

            @Override
            public CodecType codecType() {
                return CodecType.MPEG4_GENERIC;
            }

            @Override
            public TrackType trackType() {
                return TrackType.AUDIO;
            }

            @Override
            public String connectionAddress() {
                return null;
            }

            @Override
            public int clockRate() {
                return 48000;
            }

            @Override
            public byte[] aacAudioSpecificConfig() {
                return new byte[]{0x11, (byte) 0x90};
            }
        };
        byte[] accessUnit = new byte[]{0x21, 0x10, 0x56, (byte) 0xe5, 0x00};
        try {
            List<CanonicalAudioFrame> frames = canonicalizer.canonicalize(packet(accessUnit), track);
            Assertions.assertEquals(2, frames.size());
            Assertions.assertTrue(frames.get(0).configFrame());
            Assertions.assertArrayEquals(new byte[]{0x11, (byte) 0x90}, frames.get(0).payload());
            Assertions.assertFalse(frames.get(1).configFrame());
            Assertions.assertEquals(CodecType.MPEG4_GENERIC, frames.get(1).codecType());
            Assertions.assertArrayEquals(accessUnit, frames.get(1).payload());
        } finally {
            canonicalizer.close();
        }
    }

    @Test
    void extractsOpusPayloadFromRtpPacket() {
        RtspRtpAudioCanonicalizer canonicalizer = new RtspRtpAudioCanonicalizer();
        byte[] opusPayload = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};
        try {
            List<CanonicalAudioFrame> frames = canonicalizer.canonicalize(opusPacket(opusPayload), null);
            Assertions.assertEquals(1, frames.size());
            Assertions.assertFalse(frames.get(0).configFrame());
            Assertions.assertEquals(CodecType.OPUS, frames.get(0).codecType());
            Assertions.assertArrayEquals(opusPayload, frames.get(0).payload());
        } finally {
            canonicalizer.close();
        }
    }

    private InboundRtpPacket packet(byte[] accessUnit) {
        byte[] rtpPayload = new byte[4 + accessUnit.length];
        rtpPayload[0] = 0x00;
        rtpPayload[1] = 0x10;
        int sizeBits = accessUnit.length << 3;
        rtpPayload[2] = (byte) ((sizeBits >> 8) & 0xFF);
        rtpPayload[3] = (byte) (sizeBits & 0xFF);
        System.arraycopy(accessUnit, 0, rtpPayload, 4, accessUnit.length);

        byte[] packet = new byte[12 + rtpPayload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (0x80 | 97);
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x04;
        packet[7] = 0x00;
        System.arraycopy(rtpPayload, 0, packet, 12, rtpPayload.length);

        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.AUDIO,
                        CodecType.MPEG4_GENERIC,
                        "rtsp-session",
                        new StreamKey(StreamProtocol.RTSP, "live", "camera"),
                        "audio",
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                48000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(2)
        );
    }

    private InboundRtpPacket opusPacket(byte[] payloadBytes) {
        byte[] packet = new byte[12 + payloadBytes.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (0x80 | 111);
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x04;
        packet[7] = 0x00;
        System.arraycopy(payloadBytes, 0, packet, 12, payloadBytes.length);
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.WEBRTC,
                        TrackType.AUDIO,
                        CodecType.OPUS,
                        "webrtc-session",
                        new StreamKey(StreamProtocol.WEBRTC, "live", "camera"),
                        "audio-opus",
                        null,
                        null,
                        false,
                        false,
                        null,
                        packet
                ),
                48000,
                false,
                MediaPacketTransport.UDP,
                null,
                null
        );
    }
}
