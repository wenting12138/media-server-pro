package com.wenting.mediaserver.protocol.http.hls;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHlsStorageTest {

    @Test
    void shouldPersistPlaylistAndSegmentBytesToDisk() throws Exception {
        Path directory = Files.createTempDirectory("hls-storage-test");
        try {
            FileHlsStorage storage = new FileHlsStorage(directory, 3);
            HlsSegment segment = new HlsSegment(0L, 2000L, new byte[] {0x47, 0x11, 0x22});

            storage.storeCompletedSegment(segment);
            String playlist = storage.playlist(2000L, 1L);
            byte[] bytes = storage.segmentBytes(0L);

            assertTrue(playlist.contains("#EXTM3U"));
            assertTrue(playlist.contains("seg-00000.ts"));
            assertArrayEquals(segment.bytes(), bytes);
            assertTrue(Files.exists(directory.resolve("seg-00000.ts")));
            assertTrue(Files.exists(directory.resolve("seg-00000.meta")));
            assertTrue(new String(Files.readAllBytes(directory.resolve("seg-00000.meta")), StandardCharsets.UTF_8).contains("2000"));
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
