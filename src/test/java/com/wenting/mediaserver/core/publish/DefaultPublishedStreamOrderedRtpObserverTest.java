package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

final class DefaultPublishedStreamOrderedRtpObserverTest {

    @Test
    void notifiesObserverFromOrderedRtpPath() {
        DefaultPublishedStream stream = new DefaultPublishedStream(new StreamKey(StreamProtocol.RTSP, "live", "cam01"));
        AtomicReference<InboundRtpPacket> observed = new AtomicReference<InboundRtpPacket>();
        stream.setOrderedRtpPacketObserver(observed::set);

        InboundRtpPacket packet = new InboundRtpPacket(
                new InboundMediaFrame(
                        StreamProtocol.RTSP,
                        TrackType.VIDEO,
                        CodecType.H264,
                        "session-1",
                        new StreamKey(StreamProtocol.RTSP, "live", "cam01"),
                        "video",
                        null,
                        null,
                        false,
                        false,
                        null,
                        new byte[]{
                                (byte) 0x80, (byte) 0xE0, 0x00, 0x01,
                                0x00, 0x00, 0x00, 0x01,
                                0x00, 0x00, 0x00, 0x01,
                                0x65, 0x01
                        }
                ),
                90000,
                false,
                MediaPacketTransport.TCP_INTERLEAVED,
                null,
                Integer.valueOf(0)
        );

        stream.onInboundRtpPacket(packet);

        Assertions.assertSame(packet, observed.get());
    }
}
