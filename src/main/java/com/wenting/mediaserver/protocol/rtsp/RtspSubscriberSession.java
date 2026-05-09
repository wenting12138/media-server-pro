package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.remux.rtp.RtpPacketChunk;
import com.wenting.mediaserver.core.remux.rtp.RtpPayloadTypeResolver;
import com.wenting.mediaserver.core.remux.rtp.RtpSendTrackState;
import com.wenting.mediaserver.core.remux.rtp.RtspFrameToRtpPacketizer;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One RTSP subscriber session that receives media for a published stream.
 */
public final class RtspSubscriberSession {

    private final String sessionId;
    private final StreamKey streamKey;
    private final Channel controlChannel;
    private final RtpUdpPacketSender udpPacketSender;
    private final RtspFrameToRtpPacketizer frameToRtpPacketizer = new RtspFrameToRtpPacketizer();
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();
    private final Map<String, RtspTransport> transportsByTrackId = new LinkedHashMap<String, RtspTransport>();
    private final Map<String, RtpSendTrackState> rtpSendStatesByTrackId = new LinkedHashMap<String, RtpSendTrackState>();
    private final Map<String, InboundMediaFrame> latestConfigFramesByTrackId = new LinkedHashMap<String, InboundMediaFrame>();

    public RtspSubscriberSession(String sessionId, StreamKey streamKey, Channel controlChannel) {
        this(sessionId, streamKey, controlChannel, null);
    }

    public RtspSubscriberSession(String sessionId, StreamKey streamKey, Channel controlChannel, RtpUdpPacketSender udpPacketSender) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must be non-blank");
        }
        if (streamKey == null) {
            throw new IllegalArgumentException("streamKey must not be null");
        }
        if (controlChannel == null) {
            throw new IllegalArgumentException("controlChannel must not be null");
        }
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.controlChannel = controlChannel;
        this.udpPacketSender = udpPacketSender;
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public Channel controlChannel() {
        return controlChannel;
    }

    public void transport(String trackId, RtspTransport transport) {
        transportsByTrackId.put(normalizeTrackId(trackId), transport == null ? RtspTransport.unknown(null) : transport);
    }

    public RtspTransport transport(String trackId) {
        RtspTransport transport = transportsByTrackId.get(normalizeTrackId(trackId));
        return transport == null ? RtspTransport.unknown(null) : transport;
    }

    public Map<String, RtspTransport> transportsByTrackId() {
        return Collections.unmodifiableMap(transportsByTrackId);
    }

    public boolean acceptsTrack(String trackId) {
        return transportsByTrackId.containsKey(normalizeTrackId(trackId));
    }

    public void track(ITrack track) {
        if (track == null) {
            return;
        }
        tracksById.put(normalizeTrackId(track.trackId()), track);
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
        if (packet == null) {
            return;
        }
        RtspTransport transport = transport(packet.frame().trackId());
        if (transport.mode() == RtspTransportMode.RTP_TCP_INTERLEAVED) {
            writeInterleavedPacket(packet, transport);
            return;
        }
        if (transport.mode() == RtspTransportMode.RTP_UDP) {
            writeUdpPacket(packet, transport);
        }
    }

    public void writeInboundFrame(InboundMediaFrame frame) {
        if (frame == null) {
            return;
        }
        if (frame.configFrame()) {
            latestConfigFramesByTrackId.put(normalizeTrackId(frame.trackId()), frame);
        }
        ITrack track = tracksById.get(normalizeTrackId(frame.trackId()));
        List<RtpPacketChunk> chunks = frameToRtpPacketizer.packetize(
                frame,
                track,
                latestConfigFramesByTrackId.get(normalizeTrackId(frame.trackId()))
        );
        if (chunks.isEmpty()) {
            return;
        }
        RtspTransport transport = transport(frame.trackId());
        RtpSendTrackState sendState = sendState(frame.trackId());
        int payloadType = RtpPayloadTypeResolver.resolve(frame.codecType());
        int clockRate = track == null || track.clockRate() <= 0 ? defaultClockRate(frame) : track.clockRate();
        long rtpTimestamp = sendState.toRtpTimestamp(clockRate, frame.dtsMillis() == null ? frame.ptsMillis() : frame.dtsMillis());
        for (RtpPacketChunk chunk : chunks) {
            byte[] rtpPacket = buildRtpPacket(chunk.payload(), chunk.marker(), payloadType, sendState.nextSequenceNumber(), rtpTimestamp, sendState.ssrc());
            writeMediaPacket(new InboundRtpPacket(
                    new InboundMediaFrame(
                            frame.sourceProtocol(),
                            frame.trackType(),
                            frame.codecType(),
                            frame.sessionId(),
                            frame.streamKey(),
                            frame.trackId(),
                            frame.ptsMillis(),
                            frame.dtsMillis(),
                            frame.keyFrame(),
                            false,
                            frame.outOfBandParameterSetsReady(),
                            frame.remoteAddress(),
                            rtpPacket
                    ),
                    clockRate,
                    false,
                    transport.usesInterleavedTcp() ? com.wenting.mediaserver.core.enums.publish.MediaPacketTransport.TCP_INTERLEAVED : com.wenting.mediaserver.core.enums.publish.MediaPacketTransport.UDP,
                    transport.serverRtpPort(),
                    transport.interleavedRtpChannel()
            ));
        }
    }

    private void writeInterleavedPacket(InboundRtpPacket packet, RtspTransport transport) {
        Integer channel = packet.rtcp() ? transport.interleavedRtcpChannel() : transport.interleavedRtpChannel();
        if (channel == null) {
            return;
        }
        ByteBuf out = Unpooled.buffer(4 + packet.frame().payloadLength());
        out.writeByte('$');
        out.writeByte(channel.intValue());
        out.writeShort(packet.frame().payloadLength());
        out.writeBytes(packet.frame().payload());
        controlChannel.writeAndFlush(out);
    }

    private void writeUdpPacket(InboundRtpPacket packet, RtspTransport transport) {
        if (udpPacketSender == null) {
            return;
        }
        Integer clientPort = packet.rtcp() ? transport.clientRtcpPort() : transport.clientRtpPort();
        Integer serverPort = packet.rtcp() ? transport.serverRtcpPort() : transport.serverRtpPort();
        if (clientPort == null || serverPort == null) {
            return;
        }
        InetSocketAddress remoteAddress = resolveRemoteAddress(packet.frame().trackId(), clientPort.intValue());
        if (remoteAddress == null) {
            return;
        }
        udpPacketSender.send(packet, serverPort.intValue(), remoteAddress);
    }

    private InetSocketAddress resolveRemoteAddress(String trackId, int clientPort) {
        ITrack track = tracksById.get(normalizeTrackId(trackId));
        if (track != null && isUsableConnectionAddress(track.connectionAddress())) {
            return new InetSocketAddress(track.connectionAddress(), clientPort);
        }
        if (!(controlChannel.remoteAddress() instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress remoteAddress = (InetSocketAddress) controlChannel.remoteAddress();
        return new InetSocketAddress(remoteAddress.getAddress(), clientPort);
    }

    private boolean isUsableConnectionAddress(String connectionAddress) {
        if (connectionAddress == null) {
            return false;
        }
        String trimmed = connectionAddress.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return !"0.0.0.0".equals(trimmed) && !"::".equals(trimmed) && !"::0".equals(trimmed);
    }

    private static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }

    private RtpSendTrackState sendState(String trackId) {
        String normalized = normalizeTrackId(trackId);
        RtpSendTrackState state = rtpSendStatesByTrackId.get(normalized);
        if (state != null) {
            return state;
        }
        state = new RtpSendTrackState();
        rtpSendStatesByTrackId.put(normalized, state);
        return state;
    }

    private int defaultClockRate(InboundMediaFrame frame) {
        if (frame.trackType() == com.wenting.mediaserver.core.enums.publish.TrackType.VIDEO) {
            return 90000;
        }
        if (frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.G711A
                || frame.codecType() == com.wenting.mediaserver.core.enums.publish.CodecType.G711U) {
            return 8000;
        }
        return 48000;
    }

    private byte[] buildRtpPacket(byte[] payload, boolean marker, int payloadType, int sequenceNumber, long timestamp, long ssrc) {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (payloadType & 0x7F);
        if (marker) {
            packet[1] |= (byte) 0x80;
        }
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, 0, packet, 12, payload.length);
        return packet;
    }

    @Override
    public String toString() {
        return "RtspSubscriberSession{"
                + "sessionId='" + sessionId + '\''
                + ", streamKey=" + streamKey
                + ", transportsByTrackId=" + transportsByTrackId
                + '}';
    }
}
