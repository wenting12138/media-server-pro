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
 * Caches latest H265 VPS/SPS/PPS packets per track.
 */
public final class H265ParameterSetCache {

    private static final int NAL_TYPE_VPS = 32;
    private static final int NAL_TYPE_SPS = 33;
    private static final int NAL_TYPE_PPS = 34;
    private static final int NAL_TYPE_AP = 48;
    private static final int NAL_TYPE_FU = 49;

    private final Map<String, List<InboundRtpPacket>> vpsByTrackId = new ConcurrentHashMap<String, List<InboundRtpPacket>>();
    private final Map<String, List<InboundRtpPacket>> spsByTrackId = new ConcurrentHashMap<String, List<InboundRtpPacket>>();
    private final Map<String, List<InboundRtpPacket>> ppsByTrackId = new ConcurrentHashMap<String, List<InboundRtpPacket>>();
    private final Map<String, List<InboundRtpPacket>> pendingFuPacketsByTrackId =
            new ConcurrentHashMap<String, List<InboundRtpPacket>>();
    private final Map<String, Integer> pendingFuNalTypeByTrackId = new ConcurrentHashMap<String, Integer>();
    private final Map<String, Long> pendingFuTimestampByTrackId = new ConcurrentHashMap<String, Long>();

    public void cache(InboundRtpPacket packet, RtpPacketHeader header) {
        if (packet == null || header == null || header.payloadLength() < 2) {
            return;
        }
        String trackKey = trackKey(packet.frame().trackId());
        int payloadOffset = header.payloadOffset();
        byte[] payload = packet.frame().payload();
        if (payloadOffset + 1 >= payload.length) {
            return;
        }
        int nalType = nalType(payload, payloadOffset);
        if (nalType == NAL_TYPE_VPS) {
            vpsByTrackId.put(trackKey, singleton(packet));
            return;
        }
        if (nalType == NAL_TYPE_SPS) {
            spsByTrackId.put(trackKey, singleton(packet));
            return;
        }
        if (nalType == NAL_TYPE_PPS) {
            ppsByTrackId.put(trackKey, singleton(packet));
            return;
        }
        if (nalType == NAL_TYPE_AP) {
            cacheApParameterSets(packet, payloadOffset + 2, trackKey);
            return;
        }
        if (nalType == NAL_TYPE_FU) {
            cacheFuParameterSets(packet, payloadOffset, header, trackKey);
        }
    }

    public List<InboundRtpPacket> snapshot(String trackId) {
        String key = trackKey(trackId);
        List<InboundRtpPacket> packets = new ArrayList<InboundRtpPacket>(6);
        appendAll(packets, vpsByTrackId.get(key));
        appendAll(packets, spsByTrackId.get(key));
        appendAll(packets, ppsByTrackId.get(key));
        return Collections.unmodifiableList(packets);
    }

    public boolean hasAll(String trackId) {
        String key = trackKey(trackId);
        return vpsByTrackId.containsKey(key)
                && spsByTrackId.containsKey(key)
                && ppsByTrackId.containsKey(key);
    }

    private void cacheApParameterSets(InboundRtpPacket packet, int offset, String trackKey) {
        int cursor = offset;
        byte[] payload = packet.frame().payload();
        while (cursor + 2 <= payload.length) {
            int nalLength = ((payload[cursor] & 0xFF) << 8) | (payload[cursor + 1] & 0xFF);
            cursor += 2;
            if (nalLength <= 0 || cursor + nalLength > payload.length) {
                return;
            }
            int nalType = nalType(payload, cursor);
            if (nalType == NAL_TYPE_VPS) {
                vpsByTrackId.put(trackKey, singleton(packet));
            } else if (nalType == NAL_TYPE_SPS) {
                spsByTrackId.put(trackKey, singleton(packet));
            } else if (nalType == NAL_TYPE_PPS) {
                ppsByTrackId.put(trackKey, singleton(packet));
            }
            cursor += nalLength;
        }
    }

    private void cacheFuParameterSets(InboundRtpPacket packet, int payloadOffset, RtpPacketHeader header, String trackKey) {
        byte[] payload = packet.frame().payload();
        if (payloadOffset + 2 >= payload.length) {
            clearPendingFu(trackKey);
            return;
        }
        int fuHeader = payload[payloadOffset + 2] & 0xFF;
        boolean start = (fuHeader & 0x80) != 0;
        boolean end = (fuHeader & 0x40) != 0;
        int nalType = fuHeader & 0x3F;
        if (!isParameterSetNalType(nalType)) {
            if (start || pendingFuNalTypeByTrackId.containsKey(trackKey)) {
                clearPendingFu(trackKey);
            }
            return;
        }
        Long timestamp = Long.valueOf(header.timestamp());
        Integer pendingNalType = pendingFuNalTypeByTrackId.get(trackKey);
        Long pendingTimestamp = pendingFuTimestampByTrackId.get(trackKey);

        if (start) {
            List<InboundRtpPacket> fragments = new ArrayList<InboundRtpPacket>();
            fragments.add(packet);
            pendingFuPacketsByTrackId.put(trackKey, fragments);
            pendingFuNalTypeByTrackId.put(trackKey, Integer.valueOf(nalType));
            pendingFuTimestampByTrackId.put(trackKey, timestamp);
            if (end) {
                commitPendingFu(trackKey, nalType);
            }
            return;
        }

        if (pendingNalType == null
                || pendingTimestamp == null
                || pendingNalType.intValue() != nalType
                || !pendingTimestamp.equals(timestamp)) {
            clearPendingFu(trackKey);
            return;
        }

        List<InboundRtpPacket> fragments = pendingFuPacketsByTrackId.get(trackKey);
        if (fragments == null) {
            clearPendingFu(trackKey);
            return;
        }
        fragments.add(packet);
        if (end) {
            commitPendingFu(trackKey, nalType);
        }
    }

    private void commitPendingFu(String trackKey, int nalType) {
        List<InboundRtpPacket> fragments = pendingFuPacketsByTrackId.get(trackKey);
        if (fragments == null || fragments.isEmpty()) {
            clearPendingFu(trackKey);
            return;
        }
        List<InboundRtpPacket> snapshot = Collections.unmodifiableList(new ArrayList<InboundRtpPacket>(fragments));
        if (nalType == NAL_TYPE_VPS) {
            vpsByTrackId.put(trackKey, snapshot);
        } else if (nalType == NAL_TYPE_SPS) {
            spsByTrackId.put(trackKey, snapshot);
        } else if (nalType == NAL_TYPE_PPS) {
            ppsByTrackId.put(trackKey, snapshot);
        }
        clearPendingFu(trackKey);
    }

    private void clearPendingFu(String trackKey) {
        pendingFuPacketsByTrackId.remove(trackKey);
        pendingFuNalTypeByTrackId.remove(trackKey);
        pendingFuTimestampByTrackId.remove(trackKey);
    }

    private static boolean isParameterSetNalType(int nalType) {
        return nalType == NAL_TYPE_VPS || nalType == NAL_TYPE_SPS || nalType == NAL_TYPE_PPS;
    }

    private static List<InboundRtpPacket> singleton(InboundRtpPacket packet) {
        return Collections.unmodifiableList(Collections.singletonList(packet));
    }

    private static void appendAll(List<InboundRtpPacket> target, List<InboundRtpPacket> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (InboundRtpPacket packet : source) {
            if (packet != null && !target.contains(packet)) {
                target.add(packet);
            }
        }
    }

    private static int nalType(byte[] payload, int offset) {
        return (payload[offset] & 0x7E) >> 1;
    }

    private static String trackKey(String trackId) {
        return PublishStreamHelper.trackLabel(trackId);
    }
}
