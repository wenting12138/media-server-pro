package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;

import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * Protocol-neutral inbound encoded media frame metadata.
 */
public final class InboundMediaFrame {

    private final StreamProtocol sourceProtocol;
    private final TrackType trackType;
    private final CodecType codecType;
    private final String sessionId;
    private final StreamKey streamKey;
    private final String trackId;
    private final Long ptsMillis;
    private final Long dtsMillis;
    private final boolean keyFrame;
    private final boolean configFrame;
    private final boolean outOfBandParameterSetsReady;
    private final InetSocketAddress remoteAddress;
    private final byte[] payload;

    public InboundMediaFrame(
            StreamProtocol sourceProtocol,
            TrackType trackType,
            CodecType codecType,
            String sessionId,
            StreamKey streamKey,
            String trackId,
            Long ptsMillis,
            Long dtsMillis,
            boolean keyFrame,
            boolean configFrame,
            boolean outOfBandParameterSetsReady,
            InetSocketAddress remoteAddress,
            byte[] payload
    ) {
        this.sourceProtocol = sourceProtocol == null ? StreamProtocol.UNKNOWN : sourceProtocol;
        this.trackType = trackType == null ? TrackType.UNKNOWN : trackType;
        this.codecType = codecType == null ? CodecType.UNKNOWN : codecType;
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.trackId = trackId == null ? "" : trackId;
        this.ptsMillis = ptsMillis;
        this.dtsMillis = dtsMillis;
        this.keyFrame = keyFrame;
        this.configFrame = configFrame;
        this.outOfBandParameterSetsReady = outOfBandParameterSetsReady;
        this.remoteAddress = remoteAddress;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public InboundMediaFrame(
            StreamProtocol sourceProtocol,
            TrackType trackType,
            CodecType codecType,
            String sessionId,
            StreamKey streamKey,
            String trackId,
            Long ptsMillis,
            Long dtsMillis,
            boolean keyFrame,
            boolean configFrame,
            InetSocketAddress remoteAddress,
            byte[] payload
    ) {
        this(
                sourceProtocol,
                trackType,
                codecType,
                sessionId,
                streamKey,
                trackId,
                ptsMillis,
                dtsMillis,
                keyFrame,
                configFrame,
                false,
                remoteAddress,
                payload
        );
    }

    public StreamProtocol sourceProtocol() {
        return sourceProtocol;
    }

    public TrackType trackType() {
        return trackType;
    }

    public CodecType codecType() {
        return codecType;
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String trackId() {
        return trackId;
    }

    public Long ptsMillis() {
        return ptsMillis;
    }

    public Long dtsMillis() {
        return dtsMillis;
    }

    public boolean keyFrame() {
        return keyFrame;
    }

    public boolean configFrame() {
        return configFrame;
    }

    public boolean outOfBandParameterSetsReady() {
        return outOfBandParameterSetsReady;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public byte[] payload() {
        return payload;
    }

    public int payloadLength() {
        return payload.length;
    }
}
