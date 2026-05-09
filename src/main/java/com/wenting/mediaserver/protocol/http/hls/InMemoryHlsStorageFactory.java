package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;

final class InMemoryHlsStorageFactory implements HlsStorageFactory {

    @Override
    public HlsStorage create(StreamKey streamKey, int playlistSize) {
        return new InMemoryHlsStorage(playlistSize);
    }
}
