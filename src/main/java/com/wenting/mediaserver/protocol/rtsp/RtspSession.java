package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.rtsp.RtspSessionRole;
import com.wenting.mediaserver.core.enums.rtsp.RtspSessionState;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.model.sdp.SdpParser;
import com.wenting.mediaserver.core.model.sdp.SdpSessionDescription;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.track.SdpTrackMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-connection RTSP session state tracked by {@link RtspConnectionHandler}.
 */
public final class RtspSession {

    private final String sessionId;
    private RtspSessionRole role;
    private RtspSessionState state;
    private final Map<String, RtspTransport> transportsByTrackId;
    private List<ITrack> trackList;
    private StreamKey streamKey;
    private String sdpOrigin;
    private SdpSessionDescription sdpSessionDescription;
    private final long createdAtMillis;
    private long lastTouchedAtMillis;

    public RtspSession() {
        this(UUID.randomUUID().toString());
    }

    public RtspSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        this.sessionId = sessionId;
        this.role = RtspSessionRole.UNKNOWN;
        this.state = RtspSessionState.INIT;
        this.transportsByTrackId = new LinkedHashMap<String, RtspTransport>();
        this.trackList = Collections.emptyList();
        this.createdAtMillis = System.currentTimeMillis();
        this.lastTouchedAtMillis = createdAtMillis;
    }

    public String sessionId() {
        return sessionId;
    }

    public RtspSessionRole role() {
        return role;
    }

    public void role(RtspSessionRole role) {
        this.role = role == null ? RtspSessionRole.UNKNOWN : role;
        touch();
    }

    public RtspSessionState state() {
        return state;
    }

    public void state(RtspSessionState state) {
        this.state = state == null ? RtspSessionState.INIT : state;
        touch();
    }

    public RtspTransport transport() {
        RtspTransport transport = firstTransport();
        return transport == null ? RtspTransport.unknown(null) : transport;
    }

    public void transport(RtspTransport transport) {
        putTransport("", transport);
        touch();
    }

    public RtspTransport transport(String trackId) {
        RtspTransport transport = transportsByTrackId.get(normalizeTrackId(trackId));
        return transport == null ? RtspTransport.unknown(null) : transport;
    }

    public void transport(String trackId, RtspTransport transport) {
        putTransport(trackId, transport);
        touch();
    }

    public Map<String, RtspTransport> transportsByTrackId() {
        return Collections.unmodifiableMap(transportsByTrackId);
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public void streamKey(StreamKey streamKey) {
        this.streamKey = streamKey;
        touch();
    }

    public String sdpOrigin() {
        return sdpOrigin;
    }

    public SdpSessionDescription sdpSessionDescription() {
        return this.sdpSessionDescription;
    }

    public List<ITrack> trackList() {
        return trackList;
    }

    public ITrack findTrack(String trackId) {
        if (trackId == null || trackId.trim().isEmpty() || trackList.isEmpty()) {
            return null;
        }
        for (ITrack track : trackList) {
            if (track != null && trackId.trim().equals(track.trackId())) {
                return track;
            }
        }
        return null;
    }

    public void sdpOrigin(String sdpOrigin) {
        this.sdpOrigin = sdpOrigin;
        this.sdpSessionDescription = new SdpParser().parse(sdpOrigin);
        this.trackList = SdpTrackMapper.map(this.sdpSessionDescription);
        touch();
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long lastTouchedAtMillis() {
        return lastTouchedAtMillis;
    }

    public void touch() {
        this.lastTouchedAtMillis = System.currentTimeMillis();
    }

    public boolean hasStreamKey() {
        return streamKey != null;
    }

    public boolean hasTracks() {
        return !trackList.isEmpty();
    }

    public boolean isPublisher() {
        return role == RtspSessionRole.PUBLISHER;
    }

    public boolean isSubscriber() {
        return role == RtspSessionRole.SUBSCRIBER;
    }

    public boolean usesInterleavedTcp() {
        for (RtspTransport transport : transportsByTrackId.values()) {
            if (transport != null && transport.usesInterleavedTcp()) {
                return true;
            }
        }
        return false;
    }

    public RtspTransport findTransportByInterleavedChannel(int channel) {
        for (RtspTransport transport : transportsByTrackId.values()) {
            if (transport == null) {
                continue;
            }
            if (transport.interleavedRtpChannel() != null && transport.interleavedRtpChannel().intValue() == channel) {
                return transport;
            }
            if (transport.interleavedRtcpChannel() != null && transport.interleavedRtcpChannel().intValue() == channel) {
                return transport;
            }
        }
        return null;
    }

    public void close() {
        this.state = RtspSessionState.CLOSED;
        touch();
    }

    @Override
    public String toString() {
        return "RtspSession{"
                + "sessionId='" + sessionId + '\''
                + ", role=" + role
                + ", state=" + state
                + ", transportsByTrackId=" + transportsByTrackId
                + ", streamKey=" + streamKey
                + '}';
    }

    private void putTransport(String trackId, RtspTransport transport) {
        transportsByTrackId.put(normalizeTrackId(trackId), transport == null ? RtspTransport.unknown(null) : transport);
    }

    private RtspTransport firstTransport() {
        if (transportsByTrackId.isEmpty()) {
            return null;
        }
        return transportsByTrackId.values().iterator().next();
    }

    private static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }
}
