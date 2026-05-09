package com.wenting.mediaserver.protocol.http.hls;

interface HlsStorage extends AutoCloseable {

    void storeCompletedSegment(HlsSegment segment);

    void storeCurrentSegment(HlsSegment segment);

    String playlist(long targetDurationMillis, long nextSequence);

    byte[] segmentBytes(long sequence);

    @Override
    void close();
}
