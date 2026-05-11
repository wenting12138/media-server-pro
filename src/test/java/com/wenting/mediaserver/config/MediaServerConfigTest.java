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
        assertEquals("D:/workspace/github/wenting/mediaserver_data/hls", config.hlsDirectory());
        assertEquals(18081, config.webrtcUdpPort());
        assertFalse(config.hlsFileStorageEnabled());
    }

    @Test
    void shouldEnableFileBackedHlsStorageWhenConfigured() {
        MediaServerConfig config = new MediaServerConfig(18080, 1554, 11935, 20000, 30000, "file", "D:/tmp/hls");

        assertEquals("file", config.hlsStorage());
        assertEquals("D:/tmp/hls", config.hlsDirectory());
        assertTrue(config.hlsFileStorageEnabled());
    }

    @Test
    void shouldExposeConfiguredWebRtcPublicIp() {
        MediaServerConfig config = new MediaServerConfig(
                18080,
                1554,
                11935,
                18081,
                20000,
                30000,
                "memory",
                "D:/tmp/hls",
                "192.168.1.10"
        );

        assertEquals("192.168.1.10", config.webrtcPublicIp());
    }
}
