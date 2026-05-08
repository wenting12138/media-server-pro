package com.wenting.mediaserver.core.publish.video;

import com.wenting.mediaserver.core.codec.rtp.RtpPacketHeader;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.PublishStreamHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches latest H264 SPS/PPS packets per track.
 */
public final class H264ParameterSetCache {

    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;
    private static final int NAL_TYPE_STAP_A = 24;

    private final Map<String, InboundRtpPacket> spsByTrackId = new ConcurrentHashMap<String, InboundRtpPacket>();
    private final Map<String, InboundRtpPacket> ppsByTrackId = new ConcurrentHashMap<String, InboundRtpPacket>();

    public void cache(InboundRtpPacket packet, RtpPacketHeader header) {
        if (packet == null || header == null || header.payloadLength() <= 0) {
            return;
        }
        int payloadOffset = header.payloadOffset();
        if (payloadOffset >= packet.frame().payload().length) {
            return;
        }
        int nalType = packet.frame().payload()[payloadOffset] & 0x1F;
        if (nalType == NAL_TYPE_SPS) {
            spsByTrackId.put(trackKey(packet.frame().trackId()), packet);
            return;
        }
        if (nalType == NAL_TYPE_PPS) {
            ppsByTrackId.put(trackKey(packet.frame().trackId()), packet);
            return;
        }
        if (nalType == NAL_TYPE_STAP_A) {
            cacheStapAParameterSets(packet, payloadOffset + 1);
        }
    }

    public List<InboundRtpPacket> snapshot(String trackId) {
        String key = trackKey(trackId);
        List<InboundRtpPacket> packets = new ArrayList<InboundRtpPacket>(2);
        InboundRtpPacket sps = spsByTrackId.get(key);
        InboundRtpPacket pps = ppsByTrackId.get(key);
        if (sps != null) {
            packets.add(sps);
        }
        if (pps != null) {
            packets.add(pps);
        }
        return Collections.unmodifiableList(packets);
    }

    public List<InboundRtpPacket> snapshotAll() {
        List<InboundRtpPacket> packets = new ArrayList<InboundRtpPacket>(spsByTrackId.size() + ppsByTrackId.size());
        for (String trackId : spsByTrackId.keySet()) {
            packets.addAll(snapshot(trackId));
        }
        return Collections.unmodifiableList(packets);
    }

    private void cacheStapAParameterSets(InboundRtpPacket packet, int offset) {
        int cursor = offset;
        byte[] payload = packet.frame().payload();
        while (cursor + 2 <= payload.length) {
            int nalLength = ((payload[cursor] & 0xFF) << 8) | (payload[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalLength <= 0 || cursor + nalLength > payload.length) {
                return;
            }
            int nalType = payload[cursor] & 0x1F;
            if (nalType == NAL_TYPE_SPS) {
                spsByTrackId.put(trackKey(packet.frame().trackId()), packet);
            } else if (nalType == NAL_TYPE_PPS) {
                ppsByTrackId.put(trackKey(packet.frame().trackId()), packet);
            }
            cursor += nalLength;
        }
    }

    private static String trackKey(String trackId) {
        return PublishStreamHelper.trackLabel(trackId);
    }
}
