package com.wenting.mediaserver.core.codec.rtcp;

/**
 * Parsed RTCP Picture Loss Indication packet (PSFB/FMT=1).
 */
public final class RtcpPictureLossIndicationPacket implements RtcpPacket {

    private final long senderSsrc;
    private final long mediaSsrc;

    public RtcpPictureLossIndicationPacket(long senderSsrc, long mediaSsrc) {
        this.senderSsrc = senderSsrc;
        this.mediaSsrc = mediaSsrc;
    }

    @Override
    public int packetType() {
        return 206;
    }

    public long senderSsrc() {
        return senderSsrc;
    }

    public long mediaSsrc() {
        return mediaSsrc;
    }
}
