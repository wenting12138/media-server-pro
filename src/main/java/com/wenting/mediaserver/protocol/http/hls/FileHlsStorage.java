package com.wenting.mediaserver.protocol.http.hls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class FileHlsStorage implements HlsStorage {

    private final Path directory;
    private final int playlistSize;
    private HlsSegment currentSegment;

    FileHlsStorage(Path directory, int playlistSize) {
        this.directory = directory;
        this.playlistSize = playlistSize;
        ensureDirectory(directory);
    }

    @Override
    public void storeCompletedSegment(HlsSegment segment) {
        if (segment == null) {
            return;
        }
        writeSegment(segment);
        if (currentSegment != null && currentSegment.sequence() == segment.sequence()) {
            currentSegment = null;
        }
        trimSegments();
    }

    @Override
    public void storeCurrentSegment(HlsSegment segment) {
        currentSegment = segment;
        writeSegment(segment);
    }

    @Override
    public String playlist(long targetDurationMillis, long nextSequence) {
        List<HlsSegment> segments = readSegments();
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U\n");
        builder.append("#EXT-X-VERSION:3\n");
        builder.append("#EXT-X-TARGETDURATION:").append(Math.max(1L, (targetDurationMillis + 999L) / 1000L)).append('\n');
        long mediaSequence = segments.isEmpty() ? nextSequence : segments.get(0).sequence();
        builder.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n');
        for (HlsSegment segment : segments) {
            builder.append("#EXTINF:")
                    .append(String.format(Locale.US, "%.3f", segment.durationMillis() / 1000.0d))
                    .append(",\n");
            builder.append(segmentFileName(segment.sequence())).append('\n');
        }
        return builder.toString();
    }

    @Override
    public byte[] segmentBytes(long sequence) {
        Path path = directory.resolve(segmentFileName(sequence));
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        currentSegment = null;
    }

    private void writeSegment(HlsSegment segment) {
        if (segment == null) {
            return;
        }
        Path tsPath = directory.resolve(segmentFileName(segment.sequence()));
        Path metaPath = directory.resolve(segmentMetaFileName(segment.sequence()));
        try {
            Files.write(tsPath, segment.bytes());
            Files.write(metaPath, Long.toString(segment.durationMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write HLS segment " + tsPath, e);
        }
    }

    private void trimSegments() {
        List<HlsSegment> segments = readSegments();
        while (segments.size() > playlistSize) {
            HlsSegment removed = segments.remove(0);
            deleteIfExists(directory.resolve(segmentFileName(removed.sequence())));
            deleteIfExists(directory.resolve(segmentMetaFileName(removed.sequence())));
        }
    }

    private List<HlsSegment> readSegments() {
        List<Long> sequences = new ArrayList<Long>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "seg-*.meta")) {
            for (Path path : stream) {
                Long sequence = parseSequence(path.getFileName().toString());
                if (sequence != null) {
                    sequences.add(sequence);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list HLS segments in " + directory, e);
        }
        sequences.sort(Comparator.naturalOrder());
        List<HlsSegment> result = new ArrayList<HlsSegment>();
        for (Long sequence : sequences) {
            byte[] bytes = segmentBytes(sequence.longValue());
            if (bytes == null) {
                continue;
            }
            long durationMillis = readDurationMillis(sequence.longValue());
            result.add(new HlsSegment(sequence.longValue(), durationMillis, bytes));
        }
        return result;
    }

    private long readDurationMillis(long sequence) {
        Path metaPath = directory.resolve(segmentMetaFileName(sequence));
        try {
            if (!Files.exists(metaPath)) {
                return 1L;
            }
            return Long.parseLong(new String(Files.readAllBytes(metaPath), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return 1L;
        }
    }

    private static String segmentFileName(long sequence) {
        return String.format(Locale.US, "seg-%05d.ts", sequence);
    }

    private static String segmentMetaFileName(long sequence) {
        return String.format(Locale.US, "seg-%05d.meta", sequence);
    }

    private static Long parseSequence(String fileName) {
        if (fileName == null || !fileName.startsWith("seg-") || !fileName.endsWith(".meta")) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(fileName.substring(4, fileName.length() - 5)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create HLS directory " + directory, e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
