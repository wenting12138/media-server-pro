package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.gop.GopCache;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GopCacheTest {

    @Test
    void shouldStartNewGopAndExposeSnapshot() {
        GopCache cache = new GopCache(4, 64, 100);

        cache.startNewGop(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1000));
        cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 5), Long.valueOf(1040));

        List<InboundRtpPacket> snapshot = cache.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(8, cache.totalBytes());
        assertEquals(2, cache.packetCount());
        assertEquals(3, snapshot.get(0).frame().payloadLength());
        assertEquals(5, snapshot.get(1).frame().payloadLength());
    }

    @Test
    void shouldClearCacheWhenAppendWouldOverflowLimits() {
        GopCache cache = new GopCache(2, 10, 100);

        assertTrue(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 4), Long.valueOf(1000)));
        assertTrue(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 4), Long.valueOf(1010)));
        assertFalse(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 4), Long.valueOf(1020)));

        assertTrue(cache.isEmpty());
        assertEquals(0, cache.packetCount());
        assertEquals(0, cache.totalBytes());
    }

    @Test
    void shouldRejectPacketLargerThanConfiguredBudget() {
        GopCache cache = new GopCache(4, 8, 100);

        assertFalse(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 9), Long.valueOf(1000)));
        assertTrue(cache.isEmpty());
    }

    @Test
    void shouldRejectPacketOutsideTrackTimestampWindow() {
        GopCache cache = new GopCache(4, 64, 1);

        cache.startNewGop(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1000));
        assertTrue(cache.append(packet("trackID=1", TrackType.AUDIO, 48000, 2), Long.valueOf(2000)));
        assertFalse(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1091)));
        assertFalse(cache.append(packet("trackID=1", TrackType.AUDIO, 48000, 2), Long.valueOf(2049)));
        assertEquals(2, cache.packetCount());
    }

    @Test
    void shouldUseTrackClockRateToDeriveTimestampWindow() {
        GopCache cache = new GopCache(8, 128, 2);

        cache.startNewGop(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1000));
        assertTrue(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1180)));
        assertFalse(cache.append(packet("trackID=0", TrackType.VIDEO, 90000, 3), Long.valueOf(1181)));

        assertTrue(cache.append(packet("trackID=1", TrackType.AUDIO, 48000, 2), Long.valueOf(2000)));
        assertTrue(cache.append(packet("trackID=1", TrackType.AUDIO, 48000, 2), Long.valueOf(2096)));
        assertFalse(cache.append(packet("trackID=1", TrackType.AUDIO, 48000, 2), Long.valueOf(2097)));
    }

    private static InboundRtpPacket packet(String trackId, TrackType trackType, int clockRate, int payloadSize) {
        return new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        trackType,
                        trackType == TrackType.AUDIO ? CodecType.AAC : CodecType.H264,
                        "publisher-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        trackId,
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[payloadSize]
                ),
                clockRate,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );
    }
}
