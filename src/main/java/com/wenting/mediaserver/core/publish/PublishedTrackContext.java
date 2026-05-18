package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.frame.TrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.gop.GopCache;
import com.wenting.mediaserver.core.publish.payload.TrackPayloadHandler;
import com.wenting.mediaserver.core.publish.report.RtcpTrackStats;
import com.wenting.mediaserver.core.publish.report.RtpReorderBuffer;
import com.wenting.mediaserver.core.publish.video.H264ParameterSetCache;
import com.wenting.mediaserver.core.publish.video.H265ParameterSetCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-track runtime state for a published stream.
 */
public final class PublishedTrackContext {

    private final String trackId;
    private final CodecType codecType;
    private final AtomicLong packetCount = new AtomicLong();
    private final RtcpTrackStats rtcpStats;
    private final RtpReorderBuffer reorderBuffer;
    private final H264ParameterSetCache h264ParameterSetCache = new H264ParameterSetCache();
    private final H265ParameterSetCache h265ParameterSetCache = new H265ParameterSetCache();
    private final GopCache gopCache;
    private final TrackPayloadHandler payloadHandler;
    private final TrackFramePayloadHandler framePayloadHandler;
    private final List<InboundRtpPacket> pendingKeyFrameAccessUnitPackets = new ArrayList<InboundRtpPacket>();
    private volatile Long latestRtpTimestamp;
    private volatile Long latestSsrc;
    private volatile Integer clockRate;
    private volatile TrackType trackType;
    private volatile boolean outOfBandParameterSetsReady;
    private volatile Long pendingKeyFrameAccessUnitTimestamp;
    private volatile InboundMediaFrame latestConfigFrame;
    private volatile InboundMediaFrame latestKeyFrame;

    public PublishedTrackContext(
            String trackId,
            CodecType codecType,
            RtpReorderBuffer reorderBuffer,
            GopCache gopCache,
            TrackPayloadHandler payloadHandler,
            TrackFramePayloadHandler framePayloadHandler
    ) {
        this.trackId = trackId;
        this.codecType = codecType;
        this.rtcpStats = new RtcpTrackStats(trackId);
        this.reorderBuffer = reorderBuffer;
        this.gopCache = gopCache;
        this.payloadHandler = payloadHandler;
        this.framePayloadHandler = framePayloadHandler;
    }

    public String trackId() {
        return trackId;
    }

    public long incrementPacketCount() {
        return packetCount.incrementAndGet();
    }

    public CodecType codecType() {
        return codecType;
    }

    public long packetCount() {
        return packetCount.get();
    }

    public RtcpTrackStats rtcpStats() {
        return rtcpStats;
    }

    public RtpReorderBuffer reorderBuffer() {
        return reorderBuffer;
    }

    public H264ParameterSetCache h264ParameterSetCache() {
        return h264ParameterSetCache;
    }

    public H265ParameterSetCache h265ParameterSetCache() {
        return h265ParameterSetCache;
    }

    public GopCache gopCache() {
        return gopCache;
    }

    public TrackPayloadHandler payloadHandler() {
        return payloadHandler;
    }

    public TrackFramePayloadHandler framePayloadHandler() {
        return framePayloadHandler;
    }

    public List<InboundRtpPacket> pendingKeyFrameAccessUnitPackets() {
        return pendingKeyFrameAccessUnitPackets;
    }

    public Long latestRtpTimestamp() {
        return latestRtpTimestamp;
    }

    public void latestRtpTimestamp(Long latestRtpTimestamp) {
        this.latestRtpTimestamp = latestRtpTimestamp;
    }

    public Long latestSsrc() {
        return latestSsrc;
    }

    public void latestSsrc(Long latestSsrc) {
        this.latestSsrc = latestSsrc;
    }

    public Integer clockRate() {
        return clockRate;
    }

    public void clockRate(Integer clockRate) {
        this.clockRate = clockRate;
    }

    public TrackType trackType() {
        return trackType;
    }

    public void trackType(TrackType trackType) {
        this.trackType = trackType;
    }

    public boolean isTrackType(TrackType trackType) {
        return this.trackType == trackType;
    }

    public boolean isVideoTrack() {
        return trackType == TrackType.VIDEO;
    }

    public boolean isAudioTrack() {
        return trackType == TrackType.AUDIO;
    }

    public boolean outOfBandParameterSetsReady() {
        return outOfBandParameterSetsReady;
    }

    public void outOfBandParameterSetsReady(boolean outOfBandParameterSetsReady) {
        this.outOfBandParameterSetsReady = outOfBandParameterSetsReady;
    }

    public boolean hasGop() {
        return !gopCache.isEmpty();
    }

    public Long pendingKeyFrameAccessUnitTimestamp() {
        return pendingKeyFrameAccessUnitTimestamp;
    }

    public void pendingKeyFrameAccessUnitTimestamp(Long pendingKeyFrameAccessUnitTimestamp) {
        this.pendingKeyFrameAccessUnitTimestamp = pendingKeyFrameAccessUnitTimestamp;
    }

    public void clearPendingKeyFrameAccessUnit() {
        pendingKeyFrameAccessUnitPackets.clear();
        pendingKeyFrameAccessUnitTimestamp = null;
    }

    public boolean hasPendingKeyFrameAccessUnit() {
        return pendingKeyFrameAccessUnitTimestamp != null && !pendingKeyFrameAccessUnitPackets.isEmpty();
    }

    public boolean pendingKeyFrameAccessUnitTimestampEquals(Long timestamp) {
        return pendingKeyFrameAccessUnitTimestamp != null && pendingKeyFrameAccessUnitTimestamp.equals(timestamp);
    }

    public void beginPendingKeyFrameAccessUnit(InboundRtpPacket packet, Long timestamp) {
        clearPendingKeyFrameAccessUnit();
        pendingKeyFrameAccessUnitTimestamp = timestamp;
        pendingKeyFrameAccessUnitPackets.add(packet);
    }

    public void appendPendingKeyFrameAccessUnit(InboundRtpPacket packet) {
        pendingKeyFrameAccessUnitPackets.add(packet);
    }

    public void commitPendingKeyFrameAccessUnitToGop() {
        if (!hasPendingKeyFrameAccessUnit()) {
            clearPendingKeyFrameAccessUnit();
            return;
        }
        gopCache.startNewGop(pendingKeyFrameAccessUnitPackets.get(0), pendingKeyFrameAccessUnitTimestamp);
        for (int i = 1; i < pendingKeyFrameAccessUnitPackets.size(); i++) {
            gopCache.append(pendingKeyFrameAccessUnitPackets.get(i), pendingKeyFrameAccessUnitTimestamp);
        }
        clearPendingKeyFrameAccessUnit();
    }

    public Long mapToNtpMillis() {
        if (latestRtpTimestamp == null || clockRate == null) {
            return null;
        }
        return rtcpStats.mapRtpTimestampToNtpMillis(latestRtpTimestamp.longValue(), clockRate.intValue());
    }

    public InboundMediaFrame latestConfigFrame() {
        return latestConfigFrame;
    }

    public void latestConfigFrame(InboundMediaFrame latestConfigFrame) {
        this.latestConfigFrame = latestConfigFrame;
    }

    public InboundMediaFrame latestKeyFrame() {
        return latestKeyFrame;
    }

    public void latestKeyFrame(InboundMediaFrame latestKeyFrame) {
        this.latestKeyFrame = latestKeyFrame;
    }
}
