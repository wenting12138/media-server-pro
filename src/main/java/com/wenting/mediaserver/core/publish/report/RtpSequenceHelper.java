package com.wenting.mediaserver.core.publish.report;

/**
 * Helpers for comparing 16-bit RTP sequence numbers with wrap-around.
 */
public final class RtpSequenceHelper {

    private static final int MAX_SEQUENCE = 0xFFFF;
    private static final int HALF_RANGE = 0x8000;

    private RtpSequenceHelper() {
    }

    public static int next(int sequenceNumber) {
        return (sequenceNumber + 1) & MAX_SEQUENCE;
    }

    public static int forwardDistance(int fromInclusive, int toInclusive) {
        return (toInclusive - fromInclusive) & MAX_SEQUENCE;
    }

    public static boolean isOlder(int sequenceNumber, int expectedSequenceNumber) {
        int distance = forwardDistance(sequenceNumber, expectedSequenceNumber);
        return distance > 0 && distance < HALF_RANGE;
    }
}
