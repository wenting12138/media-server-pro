package com.wenting.mediaserver.protocol.http.hls;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

final class InMemoryHlsStorage implements HlsStorage {

    private final Deque<HlsSegment> completedSegments = new ArrayDeque<HlsSegment>();
    private final int playlistSize;
    private HlsSegment currentSegment;

    InMemoryHlsStorage(int playlistSize) {
        this.playlistSize = playlistSize;
    }

    @Override
    public void storeCompletedSegment(HlsSegment segment) {
        if (segment == null) {
            return;
        }
        completedSegments.addLast(segment);
        while (completedSegments.size() > playlistSize) {
            completedSegments.removeFirst();
        }
        if (currentSegment != null && currentSegment.sequence() == segment.sequence()) {
            currentSegment = null;
        }
    }

    @Override
    public void storeCurrentSegment(HlsSegment segment) {
        currentSegment = segment;
    }

    @Override
    public String playlist(long targetDurationMillis, long nextSequence) {
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U\n");
        builder.append("#EXT-X-VERSION:3\n");
        builder.append("#EXT-X-TARGETDURATION:").append(Math.max(1L, (targetDurationMillis + 999L) / 1000L)).append('\n');
        long mediaSequence = completedSegments.isEmpty() ? nextSequence : completedSegments.peekFirst().sequence();
        builder.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n');
        for (HlsSegment segment : completedSegments) {
            appendSegment(builder, segment);
        }
        if (currentSegment != null) {
            appendSegment(builder, currentSegment);
        }
        return builder.toString();
    }

    @Override
    public byte[] segmentBytes(long sequence) {
        for (HlsSegment segment : completedSegments) {
            if (segment.sequence() == sequence) {
                return segment.bytes();
            }
        }
        return currentSegment != null && currentSegment.sequence() == sequence
                ? currentSegment.bytes()
                : null;
    }

    @Override
    public void close() {
        completedSegments.clear();
        currentSegment = null;
    }

    private void appendSegment(StringBuilder builder, HlsSegment segment) {
        builder.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", segment.durationMillis() / 1000.0d))
                .append(",\n");
        builder.append("seg-").append(String.format(Locale.US, "%05d", segment.sequence())).append(".ts\n");
    }
}
