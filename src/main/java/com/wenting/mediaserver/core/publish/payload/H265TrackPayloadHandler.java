package com.wenting.mediaserver.core.publish.payload;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.video.H265RtpKeyFrameDetector;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.PublishedTrackContext;

/**
 * H265 RTP payload handler: parameter sets, keyframe access unit assembly, and GOP writes.
 */
public final class H265TrackPayloadHandler implements TrackPayloadHandler {

    private final H265RtpKeyFrameDetector keyFrameDetector = new H265RtpKeyFrameDetector();

    @Override
    public void onRtpPacket(InboundRtpPacket packet, RtpPacketHeader header, TrackPayloadContext context) {
        if (packet == null || header == null || context == null || context.trackContext() == null) {
            return;
        }
        PublishedTrackContext trackContext = context.trackContext();
        trackContext.h265ParameterSetCache().cache(packet, header);

        Long timestamp = Long.valueOf(header.timestamp());
        if (trackContext.hasPendingKeyFrameAccessUnit()
                && !trackContext.pendingKeyFrameAccessUnitTimestampEquals(timestamp)) {
            trackContext.clearPendingKeyFrameAccessUnit();
        }
        if (keyFrameDetector.isKeyFrame(packet.frame().payload(), header)) {
            trackContext.beginPendingKeyFrameAccessUnit(packet, timestamp);
            if (header.marker()) {
                commitIfReady(trackContext);
            }
            return;
        }
        if (trackContext.pendingKeyFrameAccessUnitTimestampEquals(timestamp)) {
            trackContext.appendPendingKeyFrameAccessUnit(packet);
            if (header.marker()) {
                commitIfReady(trackContext);
            }
            return;
        }
        if (trackContext.hasGop()) {
            trackContext.gopCache().append(packet, timestamp);
        }
    }

    private void commitIfReady(PublishedTrackContext trackContext) {
        if (trackContext == null) {
            return;
        }
        if (trackContext.h265ParameterSetCache().hasAll(trackContext.trackId())
                || trackContext.outOfBandParameterSetsReady()) {
            trackContext.commitPendingKeyFrameAccessUnitToGop();
            return;
        }
        trackContext.clearPendingKeyFrameAccessUnit();
    }
}
