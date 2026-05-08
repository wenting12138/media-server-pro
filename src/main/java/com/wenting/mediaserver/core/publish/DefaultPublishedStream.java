package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.codec.rtcp.RtcpPacketHeader;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReceiverReportPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtcp.RtcpSenderReportPacket;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.frame.AacTrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.frame.DefaultTrackFramePayloadContext;
import com.wenting.mediaserver.core.publish.frame.G711TrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.frame.H264TrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.frame.H265TrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.frame.PassThroughTrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.frame.TrackFramePayloadHandler;
import com.wenting.mediaserver.core.publish.gop.GopCache;
import com.wenting.mediaserver.core.publish.payload.AacTrackPayloadHandler;
import com.wenting.mediaserver.core.publish.payload.DefaultTrackPayloadContext;
import com.wenting.mediaserver.core.publish.payload.G711TrackPayloadHandler;
import com.wenting.mediaserver.core.publish.payload.H264TrackPayloadHandler;
import com.wenting.mediaserver.core.publish.payload.H265TrackPayloadHandler;
import com.wenting.mediaserver.core.publish.payload.PassThroughTrackPayloadHandler;
import com.wenting.mediaserver.core.publish.payload.TrackPayloadHandler;
import com.wenting.mediaserver.core.publish.report.AvSyncSnapshot;
import com.wenting.mediaserver.core.publish.report.RtcpTrackStats;
import com.wenting.mediaserver.core.publish.report.RtpReorderBuffer;
import com.wenting.mediaserver.core.publish.report.RtpReorderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default published stream runtime: packet ingest, buffering, sync stats, and subscriber fanout.
 */
public final class DefaultPublishedStream implements IPublishedStream {

    private static final Logger log = LoggerFactory.getLogger(DefaultPublishedStream.class);
    private static final int DEFAULT_GOP_MAX_PACKETS = 512;
    private static final int DEFAULT_GOP_MAX_BYTES = 2 * 1024 * 1024;
    private static final long DEFAULT_GOP_MAX_DURATION_MILLIS = 2000L;
    private static final int DEFAULT_REORDER_MAX_OUT_OF_ORDER_PACKETS = 32;
    private static final int DEFAULT_REORDER_MAX_BUFFERED_PACKETS = 128;

    private final StreamKey key;
    private final RtpPacketParser rtpPacketParser = new RtpPacketParser();
    private final Map<String, PublishedTrackContext> trackContextsByTrackId = new ConcurrentHashMap<String, PublishedTrackContext>();
    private final Map<String, MediaSubscriberAdapter> subscribersBySessionId = new ConcurrentHashMap<String, MediaSubscriberAdapter>();
    private final Map<String, SubscriberPlaybackState> playbackStatesBySessionId = new ConcurrentHashMap<String, SubscriberPlaybackState>();


    public DefaultPublishedStream(StreamKey key) {
        this.key = key;
    }

    @Override
    public StreamProtocol getProtocol() {
        return key == null || key.protocol() == null ? StreamProtocol.UNKNOWN : key.protocol();
    }

    @Override
    public void addSubscriber(MediaSubscriberAdapter subscriberSession) {
        if (subscriberSession == null) {
            return;
        }
        subscribersBySessionId.put(subscriberSession.sessionId(), subscriberSession);
        SubscriberPlaybackState playbackState = new SubscriberPlaybackState();
        playbackStatesBySessionId.put(subscriberSession.sessionId(), playbackState);
        if (hasReadyStartupCache()) {
            flushStartupCacheToSubscriber(subscriberSession);
            playbackState.start();
        } else {
            playbackState.waitForKeyFrame();
        }
        log.info("Subscriber added stream={} subscriber={} totalSubscribers={}",
                key, subscriberSession.sessionId(), subscribersBySessionId.size());
    }

    @Override
    public void removeSubscriber(String sessionId) {
        removeSubscriberAdapter(sessionId);
    }

    public MediaSubscriberAdapter removeSubscriberAdapter(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        MediaSubscriberAdapter removed = subscribersBySessionId.remove(sessionId);
        playbackStatesBySessionId.remove(sessionId);
        if (removed != null) {
            log.info("Subscriber removed stream={} subscriber={} totalSubscribers={}",
                    key, sessionId, subscribersBySessionId.size());
        }
        return removed;
    }

    public int subscriberCount() {
        return subscribersBySessionId.size();
    }

    @Override
    public void onInboundRtpPacket(InboundRtpPacket packet) {
        if (packet == null) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
        if (frame == null) {
            return;
        }
        long trackPacketCount = trackContext(frame).incrementPacketCount();
        if (packet.rtcp()) {
            onRtcpPacket(packet, trackPacketCount);
            fanout(packet);
            return;
        }
        if (packet.transport() == MediaPacketTransport.UDP) {
            onUdpRtpPacket(packet, trackPacketCount);
            return;
        }
        onOrderedRtpPacket(packet, trackPacketCount);
    }

    @Override
    public void onInboundFrame(InboundMediaFrame frame) {
        if (frame == null) {
            return;
        }
        PublishedTrackContext trackContext = trackContext(frame);
        trackContext.trackType(frame.trackType());
        long trackPacketCount = trackContext.incrementPacketCount();
        trackContext.framePayloadHandler().onFrame(
                frame,
                new DefaultTrackFramePayloadContext(trackContext, hasAnyVideoFrame())
        );
        logFirstFrame(frame, trackPacketCount);
        fanout(frame);
    }

    private void onUdpRtpPacket(InboundRtpPacket packet, long trackPacketCount) {
        RtpParseResult parseResult = rtpPacketParser.parse(packet.frame().payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        if (header == null) {
            onOrderedRtpPacket(packet, trackPacketCount);
            return;
        }
        RtpReorderResult reorderResult = publishedTrackContext(packet.frame().trackId()).reorderBuffer().offer(packet, header);
        if (reorderResult.lateDroppedPackets() > 0 || reorderResult.gapSkippedPackets() > 0 || reorderResult.reorderedReleasedPackets() > 0) {
            log.debug("RTP reorder stream={} session={} track={} lateDropped={} gapSkipped={} reorderedReleased={}",
                    key, packet.frame().sessionId(), PublishStreamHelper.trackLabel(packet.frame().trackId()),
                    reorderResult.lateDroppedPackets(), reorderResult.gapSkippedPackets(), reorderResult.reorderedReleasedPackets());
        }
        for (InboundRtpPacket orderedPacket : reorderResult.orderedPackets()) {
            onOrderedRtpPacket(orderedPacket, trackPacketCount);
        }
    }

    private void onOrderedRtpPacket(InboundRtpPacket packet, long trackPacketCount) {
        RtpParseResult parseResult = rtpPacketParser.parse(packet.frame().payload());
        RtpPacketHeader header = parseResult == null ? null : parseResult.rtpHeader();
        PublishedTrackContext trackContext = trackContext(packet.frame());
        rememberTrackTiming(trackContext, packet, header);
        if (header != null) {
            trackContext.payloadHandler().onRtpPacket(
                    packet,
                    header,
                    new DefaultTrackPayloadContext(trackContext, hasAnyVideoGop())
            );
        }
        logFirstPacket(packet, trackPacketCount, "RTP");
        logRtpDebug(packet, header);
        fanout(packet);
    }

    private void onRtcpPacket(InboundRtpPacket packet, long trackPacketCount) {
        RtpParseResult parseResult = rtpPacketParser.parse(packet.frame().payload());
        RtcpPacketHeader header = parseResult == null ? null : parseResult.rtcpHeader();
        updateRtcpStats(packet, parseResult);
        PublishedTrackContext trackContext = trackContext(packet.frame());
        trackContext.payloadHandler().onRtcpPacket(
                packet,
                new DefaultTrackPayloadContext(trackContext, hasAnyVideoGop())
        );
        logFirstPacket(packet, trackPacketCount, "RTCP");
    }

    private void logFirstPacket(InboundRtpPacket packet, long trackPacketCount, String packetKind) {
        if (trackPacketCount != 1) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
        String transport = packet.transport() == null ? "UNKNOWN" : packet.transport().name();
        log.info("First {} {} packet stream={} session={} track={} localPort={} channel={} remote={} bytes={}",
                transport, packetKind, key, frame.sessionId(), PublishStreamHelper.trackLabel(frame.trackId()),
                packet.localPort(), packet.interleavedChannel(), frame.remoteAddress(), frame.payloadLength());
    }

    private void logRtpDebug(InboundRtpPacket packet, RtpPacketHeader header) {
        if (header == null) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
    }

    private void logFirstFrame(InboundMediaFrame frame, long trackPacketCount) {
        if (trackPacketCount != 1) {
            return;
        }
        log.info("First FRAME packet stream={} session={} track={} remote={} bytes={} keyFrame={} configFrame={}",
                key, frame.sessionId(), PublishStreamHelper.trackLabel(frame.trackId()),
                frame.remoteAddress(), frame.payloadLength(), frame.keyFrame(), frame.configFrame());
    }

    private PublishedTrackContext trackContext(InboundMediaFrame frame) {
        String key = PublishStreamHelper.trackLabel(frame.trackId());
        PublishedTrackContext context = trackContextsByTrackId.get(key);
        if (context != null) {
            return context;
        }
        PublishedTrackContext created = new PublishedTrackContext(
                key,
                frame.codecType(),
                new RtpReorderBuffer(
                        DEFAULT_REORDER_MAX_OUT_OF_ORDER_PACKETS,
                        DEFAULT_REORDER_MAX_BUFFERED_PACKETS
                ),
                new GopCache(
                        DEFAULT_GOP_MAX_PACKETS,
                        DEFAULT_GOP_MAX_BYTES,
                        DEFAULT_GOP_MAX_DURATION_MILLIS
                ),
                resolvePayloadHandler(frame),
                resolveFramePayloadHandler(frame)
        );
        PublishedTrackContext existing = trackContextsByTrackId.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    PublishedTrackContext publishedTrackContext(String trackId) {
        return trackContextsByTrackId.get(PublishStreamHelper.trackLabel(trackId));
    }

    private void fanout(InboundRtpPacket packet) {
        if (subscribersBySessionId.isEmpty()) {
            return;
        }
        InboundMediaFrame frame = packet.frame();
        for (MediaSubscriberAdapter subscriber : subscribersBySessionId.values()) {
            if (subscriber == null || !subscriber.acceptsTrack(frame.trackId())) {
                continue;
            }
            SubscriberPlaybackState playbackState = playbackStatesBySessionId.get(subscriber.sessionId());
            if (playbackState == null) {
                subscriber.writeMediaPacket(packet);
                continue;
            }
            if (playbackState.started()) {
                subscriber.writeMediaPacket(packet);
                continue;
            }
            if (shouldStartSubscriberNow(packet)) {
                flushStartupCacheToSubscriber(subscriber);
                playbackState.start();
            }
        }
    }

    private void fanout(InboundMediaFrame frame) {
        if (subscribersBySessionId.isEmpty()) {
            return;
        }
        for (MediaSubscriberAdapter subscriber : subscribersBySessionId.values()) {
            if (subscriber == null || !subscriber.acceptsTrack(frame.trackId())) {
                continue;
            }
            SubscriberPlaybackState playbackState = playbackStatesBySessionId.get(subscriber.sessionId());
            if (playbackState == null) {
                subscriber.writeInboundFrame(frame);
                continue;
            }
            if (playbackState.started()) {
                subscriber.writeInboundFrame(frame);
                continue;
            }
            if (shouldStartSubscriberNow(frame)) {
                flushStartupCacheToSubscriber(subscriber);
                playbackState.start();
            }
        }
    }

    List<InboundRtpPacket> currentGopSnapshot() {
        List<InboundRtpPacket> snapshot = new java.util.ArrayList<InboundRtpPacket>();
        appendGopSnapshotByType(snapshot, TrackType.VIDEO);
        appendGopSnapshotByType(snapshot, TrackType.AUDIO);
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext == null || trackContext.isVideoTrack() || trackContext.isAudioTrack()) {
                continue;
            }
            snapshot.addAll(trackContext.gopCache().snapshot());
        }
        return snapshot;
    }

    RtcpTrackStats rtcpStats(String trackId) {
        PublishedTrackContext context = publishedTrackContext(trackId);
        return context == null ? null : context.rtcpStats();
    }

    public Map<String, RtcpTrackStats> rtcpStatsSnapshot() {
        Map<String, RtcpTrackStats> snapshot = new LinkedHashMap<String, RtcpTrackStats>();
        for (Map.Entry<String, PublishedTrackContext> entry : trackContextsByTrackId.entrySet()) {
            if (entry != null && entry.getValue() != null) {
                snapshot.put(entry.getKey(), entry.getValue().rtcpStats());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public AvSyncSnapshot avSyncSnapshot() {
        PublishedTrackContext videoTrackContext = findTrackContextByType(TrackType.VIDEO);
        PublishedTrackContext audioTrackContext = findTrackContextByType(TrackType.AUDIO);
        if (videoTrackContext == null || audioTrackContext == null) {
            return null;
        }
        Long videoNtpMillis = videoTrackContext.mapToNtpMillis();
        Long audioNtpMillis = audioTrackContext.mapToNtpMillis();
        if (videoNtpMillis == null || audioNtpMillis == null) {
            return null;
        }
        return new AvSyncSnapshot(
                videoTrackContext.trackId(),
                audioTrackContext.trackId(),
                videoNtpMillis.longValue(),
                audioNtpMillis.longValue()
        );
    }

    private boolean shouldStartSubscriberNow(InboundRtpPacket packet) {
        if (packet == null || packet.rtcp()) {
            return false;
        }
        PublishedTrackContext trackContext = publishedTrackContext(packet.frame().trackId());
        return trackContext != null
                && trackContext.isVideoTrack()
                && trackContext.hasGop()
                && !trackContext.hasPendingKeyFrameAccessUnit();
    }

    private boolean shouldStartSubscriberNow(InboundMediaFrame frame) {
        if (frame == null) {
            return false;
        }
        PublishedTrackContext trackContext = publishedTrackContext(frame.trackId());
        if (trackContext == null) {
            return false;
        }
        if (trackContext.isVideoTrack()) {
            return frame.keyFrame() && !frame.configFrame();
        }
        return !hasAnyVideoFrame() && frame.trackType() == TrackType.AUDIO;
    }

    private void flushGopCacheToSubscriber(MediaSubscriberAdapter subscriber) {
        if (subscriber == null) {
            return;
        }
        for (InboundRtpPacket cachedPacket : currentGopSnapshot()) {
            if (cachedPacket == null || !subscriber.acceptsTrack(cachedPacket.frame().trackId())) {
                continue;
            }
            subscriber.writeMediaPacket(cachedPacket);
        }
    }

    private void flushStartupCacheToSubscriber(MediaSubscriberAdapter subscriber) {
        if (subscriber == null) {
            return;
        }
        flushFrameStartupCacheToSubscriber(subscriber);
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext == null) {
                continue;
            }
            for (InboundRtpPacket parameterSetPacket : trackContext.h264ParameterSetCache().snapshot(trackContext.trackId())) {
                if (parameterSetPacket != null && subscriber.acceptsTrack(parameterSetPacket.frame().trackId())) {
                    subscriber.writeMediaPacket(parameterSetPacket);
                }
            }
            for (InboundRtpPacket parameterSetPacket : trackContext.h265ParameterSetCache().snapshot(trackContext.trackId())) {
                if (parameterSetPacket != null && subscriber.acceptsTrack(parameterSetPacket.frame().trackId())) {
                    subscriber.writeMediaPacket(parameterSetPacket);
                }
            }
        }
        flushGopCacheToSubscriber(subscriber);
    }

    private void flushFrameStartupCacheToSubscriber(MediaSubscriberAdapter subscriber) {
        appendFrameStartupCacheByType(subscriber, TrackType.VIDEO);
        appendFrameStartupCacheByType(subscriber, TrackType.AUDIO);
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext == null || trackContext.isVideoTrack() || trackContext.isAudioTrack()) {
                continue;
            }
            writeFrameStartupCache(subscriber, trackContext);
        }
    }

    private void updateRtcpStats(InboundRtpPacket packet, RtpParseResult parseResult) {
        if (packet == null || parseResult == null || !parseResult.rtcp()) {
            return;
        }
        RtcpPacket rtcpPacket = parseResult.rtcpPacket();
        if (rtcpPacket == null) {
            return;
        }
        PublishedTrackContext trackContext = trackContext(packet.frame());
        RtcpTrackStats stats = trackContext.rtcpStats();
        long now = System.currentTimeMillis();
        if (rtcpPacket instanceof RtcpSenderReportPacket) {
            RtcpSenderReportPacket senderReport = (RtcpSenderReportPacket) rtcpPacket;
            stats.updateSenderReport(senderReport, now);
            for (RtcpReportBlock reportBlock : senderReport.reportBlocks()) {
                stats.updateReportBlock(reportBlock, now);
            }
            return;
        }
        if (rtcpPacket instanceof RtcpReceiverReportPacket) {
            RtcpReceiverReportPacket receiverReport = (RtcpReceiverReportPacket) rtcpPacket;
            for (RtcpReportBlock reportBlock : receiverReport.reportBlocks()) {
                stats.updateReportBlock(reportBlock, now);
            }
        }
    }

    private void rememberTrackTiming(PublishedTrackContext context, InboundRtpPacket packet, RtpPacketHeader header) {
        if (context == null || packet == null || header == null) {
            return;
        }
        context.latestRtpTimestamp(Long.valueOf(header.timestamp()));
        context.clockRate(Integer.valueOf(packet.clockRate()));
        context.trackType(packet.frame().trackType());
        if (packet.frame().outOfBandParameterSetsReady()) {
            context.outOfBandParameterSetsReady(true);
        }
    }

    private PublishedTrackContext findTrackContextByType(TrackType trackType) {
        for (Map.Entry<String, PublishedTrackContext> entry : trackContextsByTrackId.entrySet()) {
            if (entry != null && entry.getValue() != null && entry.getValue().isTrackType(trackType)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean hasAnyVideoGop() {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null && trackContext.isVideoTrack() && trackContext.hasGop()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyVideoFrame() {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null
                    && trackContext.isVideoTrack()
                    && (trackContext.latestConfigFrame() != null || trackContext.latestKeyFrame() != null)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasReadyStartupCache() {
        if (!currentGopSnapshot().isEmpty()) {
            return true;
        }
        if (hasAnyReadyVideoFrame()) {
            return true;
        }
        return !hasAnyVideoFrame() && hasAnyAudioFrame();
    }

    private boolean hasAnyReadyVideoFrame() {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null
                    && trackContext.isVideoTrack()
                    && trackContext.latestKeyFrame() != null
                    && !trackContext.latestKeyFrame().configFrame()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyAudioFrame() {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null
                    && trackContext.isAudioTrack()
                    && (trackContext.latestConfigFrame() != null || trackContext.latestKeyFrame() != null)) {
                return true;
            }
        }
        return false;
    }

    private void appendGopSnapshotByType(List<InboundRtpPacket> snapshot, TrackType trackType) {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null && trackContext.isTrackType(trackType)) {
                snapshot.addAll(trackContext.gopCache().snapshot());
            }
        }
    }

    private void appendFrameStartupCacheByType(MediaSubscriberAdapter subscriber, TrackType trackType) {
        for (PublishedTrackContext trackContext : trackContextsByTrackId.values()) {
            if (trackContext != null && trackContext.isTrackType(trackType)) {
                writeFrameStartupCache(subscriber, trackContext);
            }
        }
    }

    private void writeFrameStartupCache(MediaSubscriberAdapter subscriber, PublishedTrackContext trackContext) {
        if (subscriber == null || trackContext == null) {
            return;
        }
        InboundMediaFrame configFrame = trackContext.latestConfigFrame();
        if (configFrame != null && subscriber.acceptsTrack(configFrame.trackId())) {
            subscriber.writeInboundFrame(configFrame);
        }
        InboundMediaFrame keyFrame = trackContext.latestKeyFrame();
        if (keyFrame != null
                && !keyFrame.configFrame()
                && keyFrame != configFrame
                && subscriber.acceptsTrack(keyFrame.trackId())) {
            subscriber.writeInboundFrame(keyFrame);
        }
    }

    private TrackPayloadHandler resolvePayloadHandler(InboundMediaFrame frame) {
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H264) {
            return new H264TrackPayloadHandler();
        }
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H265) {
            return new H265TrackPayloadHandler();
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.AAC || frame.codecType() == CodecType.MPEG4_GENERIC)) {
            return AacTrackPayloadHandler.INSTANCE;
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.G711A || frame.codecType() == CodecType.G711U)) {
            return G711TrackPayloadHandler.INSTANCE;
        }
        return PassThroughTrackPayloadHandler.INSTANCE;
    }

    private TrackFramePayloadHandler resolveFramePayloadHandler(InboundMediaFrame frame) {
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H264) {
            return H264TrackFramePayloadHandler.INSTANCE;
        }
        if (frame.trackType() == TrackType.VIDEO && frame.codecType() == CodecType.H265) {
            return H265TrackFramePayloadHandler.INSTANCE;
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.AAC || frame.codecType() == CodecType.MPEG4_GENERIC)) {
            return AacTrackFramePayloadHandler.INSTANCE;
        }
        if (frame.trackType() == TrackType.AUDIO
                && (frame.codecType() == CodecType.G711A || frame.codecType() == CodecType.G711U)) {
            return G711TrackFramePayloadHandler.INSTANCE;
        }
        return PassThroughTrackFramePayloadHandler.INSTANCE;
    }

}
