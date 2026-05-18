package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.report.AvSyncSnapshot;
import com.wenting.mediaserver.core.publish.report.RtcpTrackStats;

import java.util.Collections;
import java.util.Map;

public interface IPublishedStream {

    public StreamProtocol getProtocol();

    public void onInboundRtpPacket(InboundRtpPacket packet);

    public void onInboundFrame(InboundMediaFrame frame);

    public void addSubscriber(MediaSubscriberAdapter subscriber);

    public void removeSubscriber(String sessionId);

    default boolean requestKeyFrame(String trackId) {
        return false;
    }

    default Long latestTrackSsrc(String trackId) {
        return null;
    }

    default String firstVideoTrackId() {
        return null;
    }

    default String sdpDescription(StreamKey requestStreamKey) {
        return null;
    }
}
