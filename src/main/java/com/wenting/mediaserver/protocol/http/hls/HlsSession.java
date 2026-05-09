package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.track.ITrack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HlsSession implements MediaSubscriberAdapter {

    private final String sessionId;
    private final StreamKey streamKey;
    private final RtpToRtmpFrameAssembler rtpToFrameAssembler = new RtpToRtmpFrameAssembler();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();
    private final Deque<HlsSegment> completedSegments = new ArrayDeque<HlsSegment>();
    private final TsMuxer currentMuxer = new TsMuxer();
    private final int playlistSize;
    private final long targetDurationMillis;
    private long nextSequence = 0L;
    private Long currentSegmentStartDtsMillis;
    private HlsSegment currentSnapshotSegment;

    HlsSession(String sessionId, StreamKey streamKey, int playlistSize, long targetDurationMillis) {
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.playlistSize = playlistSize;
        this.targetDurationMillis = targetDurationMillis;
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
            refreshCurrentSnapshot(frame == null ? 0L : frame.dtsMillis());
            return;
        }
        maybeRotateSegment(frame);
        currentMuxer.onFrame(frame);
        refreshCurrentSnapshot(frame.dtsMillis());
    }

    synchronized String playlist() {
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U\n");
        builder.append("#EXT-X-VERSION:3\n");
        builder.append("#EXT-X-TARGETDURATION:").append(Math.max(1L, (targetDurationMillis + 999L) / 1000L)).append('\n');
        long mediaSequence = completedSegments.isEmpty() ? nextSequence : completedSegments.peekFirst().sequence();
        builder.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n');
        for (HlsSegment segment : completedSegments) {
            appendSegment(builder, segment);
        }
        if (currentSnapshotSegment != null) {
            appendSegment(builder, currentSnapshotSegment);
        }
        return builder.toString();
    }

    synchronized byte[] segmentBytes(long sequence) {
        for (HlsSegment segment : completedSegments) {
            if (segment.sequence() == sequence) {
                return segment.bytes();
            }
        }
        return currentSnapshotSegment != null && currentSnapshotSegment.sequence() == sequence
                ? currentSnapshotSegment.bytes()
                : null;
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
        completedSegments.addLast(new HlsSegment(nextSequence++, Math.max(elapsed, 1L), bytes));
        while (completedSegments.size() > playlistSize) {
            completedSegments.removeFirst();
        }
        currentMuxerReset();
        currentSegmentStartDtsMillis = dts;
        currentSnapshotSegment = null;
    }

    private void currentMuxerReset() {
        currentMuxer.reset();
    }

    private void refreshCurrentSnapshot(Long dts) {
        byte[] bytes = currentMuxer.bytes();
        if (bytes.length == 0) {
            currentSnapshotSegment = null;
            return;
        }
        long duration = 1L;
        if (currentSegmentStartDtsMillis != null && dts != null) {
            duration = Math.max(1L, dts.longValue() - currentSegmentStartDtsMillis.longValue());
        }
        currentSnapshotSegment = new HlsSegment(nextSequence, duration, bytes);
    }

    private void appendSegment(StringBuilder builder, HlsSegment segment) {
        builder.append("#EXTINF:")
                .append(String.format(java.util.Locale.US, "%.3f", segment.durationMillis() / 1000.0d))
                .append(",\n");
        builder.append("seg-").append(String.format(java.util.Locale.US, "%05d", segment.sequence())).append(".ts\n");
    }
}
