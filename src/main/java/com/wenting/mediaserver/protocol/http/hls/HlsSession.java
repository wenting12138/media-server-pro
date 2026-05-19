package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.track.ITrack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HlsSession implements MediaSubscriberAdapter {

    private final String sessionId;
    private final StreamKey streamKey;
    private final RtpToRtmpFrameAssembler rtpToFrameAssembler = new RtpToRtmpFrameAssembler();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();
    private final TsMuxer currentMuxer = new TsMuxer();
    private final HlsStorage storage;
    private final int playlistSize;
    private final long targetDurationMillis;
    private long nextSequence = 0L;
    private Long currentSegmentStartDtsMillis;

    HlsSession(String sessionId, StreamKey streamKey, int playlistSize, long targetDurationMillis, HlsStorage storage) {
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.playlistSize = playlistSize;
        this.targetDurationMillis = targetDurationMillis;
        this.storage = storage;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean acceptsTrack(String trackId) {
        return true;
    }

    void track(ITrack track) {
        if (track == null || track.trackId() == null) {
            return;
        }
        tracksById.put(track.trackId().trim(), track);
    }

    @Override
    public synchronized void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || packet.frame() == null) {
            return;
        }
        List<InboundMediaFrame> frames = rtpToFrameAssembler.assemble(packet, tracksById.get(packet.frame().trackId()));
        for (InboundMediaFrame frame : frames) {
            writeInboundFrame(frame);
        }
    }

    @Override
    public synchronized void writeInboundFrame(InboundMediaFrame frame) {
        if (frame == null || frame.configFrame()) {
            currentMuxer.onFrame(frame);
            refreshCurrentSnapshot(frame == null ? Long.valueOf(0L) : frame.dtsMillis());
            return;
        }
        maybeRotateSegment(frame);
        currentMuxer.onFrame(frame);
        refreshCurrentSnapshot(frame.dtsMillis());
    }

    synchronized String playlist() {
        return storage.playlist(targetDurationMillis, nextSequence);
    }

    synchronized byte[] segmentBytes(long sequence) {
        return storage.segmentBytes(sequence);
    }

    synchronized void close() {
        storage.close();
    }

    private void maybeRotateSegment(InboundMediaFrame frame) {
        Long dts = frame.dtsMillis() == null ? frame.ptsMillis() : frame.dtsMillis();
        if (dts == null) {
            dts = Long.valueOf(0L);
        }
        if (currentSegmentStartDtsMillis == null) {
            currentSegmentStartDtsMillis = dts;
            return;
        }
        if (!frame.keyFrame()) {
            return;
        }
        long elapsed = dts.longValue() - currentSegmentStartDtsMillis.longValue();
        if (elapsed < targetDurationMillis) {
            return;
        }
        byte[] bytes = currentMuxer.bytes();
        if (bytes.length == 0) {
            currentSegmentStartDtsMillis = dts;
            return;
        }
        storage.storeCompletedSegment(new HlsSegment(nextSequence++, Math.max(elapsed, 1L), bytes));
        currentMuxerReset();
        currentSegmentStartDtsMillis = dts;
        storage.storeCurrentSegment(null);
    }

    private void currentMuxerReset() {
        currentMuxer.reset();
    }

    private void refreshCurrentSnapshot(Long dts) {
        byte[] bytes = currentMuxer.bytes();
        if (bytes.length == 0) {
            storage.storeCurrentSegment(null);
            return;
        }
        long duration = 1L;
        if (currentSegmentStartDtsMillis != null && dts != null) {
            duration = Math.max(1L, dts.longValue() - currentSegmentStartDtsMillis.longValue());
        }
        storage.storeCurrentSegment(new HlsSegment(nextSequence, duration, bytes));
    }
}
