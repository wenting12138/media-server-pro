package com.wenting.mediaserver.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaServerConfigTest {

    @Test
    void shouldUseDefaultHlsStorageSettingsInLegacyConstructor() {
        MediaServerConfig config = new MediaServerConfig(18080, 1554, 11935, 20000, 30000);

        assertEquals("memory", config.hlsStorage());
        assertEquals("target/hls", config.hlsDirectory());
        assertFalse(config.hlsFileStorageEnabled());
    }

    @Test
    void shouldEnableFileBackedHlsStorageWhenConfigured() {
        MediaServerConfig config = new MediaServerConfig(18080, 1554, 11935, 20000, 30000, "file", "D:/tmp/hls");

        assertEquals("file", config.hlsStorage());
        assertEquals("D:/tmp/hls", config.hlsDirectory());
        assertTrue(config.hlsFileStorageEnabled());
    }
}
