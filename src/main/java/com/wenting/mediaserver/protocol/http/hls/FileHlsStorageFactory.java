package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;

import java.nio.file.Path;

final class FileHlsStorageFactory implements HlsStorageFactory {

    private final Path rootDirectory;

    FileHlsStorageFactory(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    @Override
    public HlsStorage create(StreamKey streamKey, int playlistSize) {
        return new FileHlsStorage(rootDirectory.resolve(streamKey.app()).resolve(streamKey.stream()), playlistSize);
    }
}
