package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.video.H264RtpKeyFrameDetector;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.PublishedTrackContext;

/**
 * H264 RTP payload handler: parameter sets, keyframe access unit assembly, and GOP writes.
 */
public final class H264TrackPayloadHandler implements TrackPayloadHandler {

    private final H264RtpKeyFrameDetector keyFrameDetector = new H264RtpKeyFrameDetector();

    @Override
    public void onRtpPacket(InboundRtpPacket packet, RtpPacketHeader header, TrackPayloadContext context) {
        if (packet == null || header == null || context == null || context.trackContext() == null) {
            return;
        }
        PublishedTrackContext trackContext = context.trackContext();
        trackContext.h264ParameterSetCache().cache(packet, header);

        Long timestamp = Long.valueOf(header.timestamp());
        if (trackContext.hasPendingKeyFrameAccessUnit()
                && !trackContext.pendingKeyFrameAccessUnitTimestampEquals(timestamp)) {
            trackContext.clearPendingKeyFrameAccessUnit();
        }
        if (keyFrameDetector.isKeyFrame(packet.frame().payload(), header)) {
            trackContext.beginPendingKeyFrameAccessUnit(packet, timestamp);
            if (header.marker()) {
                trackContext.commitPendingKeyFrameAccessUnitToGop();
            }
            return;
        }
        if (trackContext.pendingKeyFrameAccessUnitTimestampEquals(timestamp)) {
            trackContext.appendPendingKeyFrameAccessUnit(packet);
            if (header.marker()) {
                trackContext.commitPendingKeyFrameAccessUnitToGop();
            }
            return;
        }
        if (trackContext.hasGop()) {
            trackContext.gopCache().append(packet, timestamp);
        }
    }
}
