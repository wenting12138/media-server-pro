package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;

/**
 * RTP-family inbound packet metadata layered on top of a protocol-neutral media frame.
 */
public final class InboundRtpPacket {

    private final InboundMediaFrame frame;
    private final int clockRate;
    private final boolean rtcp;
    private final MediaPacketTransport transport;
    private final Integer localPort;
    private final Integer interleavedChannel;

    public InboundRtpPacket(
            InboundMediaFrame frame,
            int clockRate,
            boolean rtcp,
            MediaPacketTransport transport,
            Integer localPort,
            Integer interleavedChannel
    ) {
        this.frame = frame == null ? new InboundMediaFrame(
                null,
                null,
                null,
                null,
                null,
                "",
                null,
                null,
                false,
                false,
                null,
                null
        ) : frame;
        this.clockRate = Math.max(clockRate, 0);
        this.rtcp = rtcp;
        this.transport = transport;
        this.localPort = localPort;
        this.interleavedChannel = interleavedChannel;
    }

    public InboundMediaFrame frame() {
        return frame;
    }

    public int clockRate() {
        return clockRate;
    }

    public boolean rtcp() {
        return rtcp;
    }

    public MediaPacketTransport transport() {
        return transport;
    }

    public Integer localPort() {
        return localPort;
    }

    public Integer interleavedChannel() {
        return interleavedChannel;
    }
}
