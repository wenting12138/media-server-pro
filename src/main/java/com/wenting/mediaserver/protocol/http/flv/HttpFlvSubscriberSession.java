package com.wenting.mediaserver.protocol.http.flv;

import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.remux.rtmp.RtpToRtmpFrameAssembler;
import com.wenting.mediaserver.core.track.ITrack;
import io.netty.channel.Channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpFlvSubscriberSession {

    private final String sessionId;
    private final StreamKey streamKey;
    private final FlvWriter flvWriter;
    private final FlvPayloadBuilder flvPayloadBuilder = new FlvPayloadBuilder();
    private final RtpToRtmpFrameAssembler rtpToRtmpFrameAssembler = new RtpToRtmpFrameAssembler();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();

    public HttpFlvSubscriberSession(String sessionId, StreamKey streamKey, Channel channel) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.flvWriter = new FlvWriter(channel);
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean isActive() {
        return flvWriter.isActive();
    }

    public void track(ITrack track) {
        if (track == null || track.trackId() == null) {
            return;
        }
        tracksById.put(track.trackId().trim(), track);
    }

    public void startResponse() {
        flvWriter.startHttpFlvResponse();
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null || !isActive()) {
            return;
        }
        List<InboundMediaFrame> frames = rtpToRtmpFrameAssembler.assemble(packet, tracksById.get(packet.frame().trackId()));
        for (InboundMediaFrame frame : frames) {
            writeInboundFrame(frame);
        }
    }

    public void writeInboundFrame(InboundMediaFrame frame) {
        if (frame == null || !isActive()) {
            return;
        }
        if (!flvWriter.responseStarted()) {
            startResponse();
        }
        if (frame.trackType() == TrackType.VIDEO) {
            writeFlvTag((byte) 0x09, flvPayloadBuilder.toVideoPayload(frame), flvPayloadBuilder.resolveTimestamp(frame));
            return;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            writeFlvTag((byte) 0x08, flvPayloadBuilder.toAudioPayload(frame), flvPayloadBuilder.resolveTimestamp(frame));
        }
    }

    private void writeFlvTag(byte tagType, byte[] payload, long timestamp) {
        flvWriter.writeTag(tagType, payload, timestamp);
    }
}
