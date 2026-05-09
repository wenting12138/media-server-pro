package com.wenting.mediaserver.protocol.http.hls;

import com.wenting.mediaserver.core.model.StreamKey;

interface HlsStorageFactory {

    HlsStorage create(StreamKey streamKey, int playlistSize);
}
