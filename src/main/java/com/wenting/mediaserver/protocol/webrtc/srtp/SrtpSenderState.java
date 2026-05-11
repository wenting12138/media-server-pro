package com.wenting.mediaserver.protocol.webrtc.srtp;

final class SrtpSenderState {

    private int highestSequenceNumber = -1;
    private long rolloverCounter;

    long nextPacketIndex(int sequenceNumber) {
        int normalizedSequenceNumber = sequenceNumber & 0xFFFF;
        if (highestSequenceNumber >= 0 && normalizedSequenceNumber < highestSequenceNumber
                && highestSequenceNumber - normalizedSequenceNumber > 0x8000) {
            rolloverCounter++;
        }
        highestSequenceNumber = normalizedSequenceNumber;
        return ((rolloverCounter & 0xFFFFFFFFL) << 16) | (normalizedSequenceNumber & 0xFFFFL);
    }
}
