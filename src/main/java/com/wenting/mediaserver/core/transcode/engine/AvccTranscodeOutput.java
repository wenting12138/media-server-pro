package com.wenting.mediaserver.core.transcode.engine;

final class AvccTranscodeOutput {

    final byte[] payloadBytes;
    final byte[] sequenceHeaderBytes;
    final boolean keyFrame;

    AvccTranscodeOutput(byte[] payloadBytes, byte[] sequenceHeaderBytes, boolean keyFrame) {
        this.payloadBytes = payloadBytes;
        this.sequenceHeaderBytes = sequenceHeaderBytes;
        this.keyFrame = keyFrame;
    }
}
