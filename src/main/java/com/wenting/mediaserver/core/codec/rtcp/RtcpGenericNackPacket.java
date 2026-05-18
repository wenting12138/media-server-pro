package com.wenting.mediaserver.core.codec.rtcp;

import java.util.Collections;
import java.util.List;

/**
 * Parsed RTCP Generic NACK packet (RTPFB/FMT=1).
 */
public final class RtcpGenericNackPacket implements RtcpPacket {

    private final long senderSsrc;
    private final long mediaSsrc;
    private final List<Integer> lostSequenceNumbers;

    public RtcpGenericNackPacket(long senderSsrc, long mediaSsrc, List<Integer> lostSequenceNumbers) {
        this.senderSsrc = senderSsrc;
        this.mediaSsrc = mediaSsrc;
        this.lostSequenceNumbers = lostSequenceNumbers == null
                ? Collections.<Integer>emptyList()
                : Collections.unmodifiableList(lostSequenceNumbers);
    }

    @Override
    public int packetType() {
        return 205;
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public long mediaSsrc() {
        return mediaSsrc;
    }

    public List<Integer> lostSequenceNumbers() {
        return lostSequenceNumbers;
    }
}
