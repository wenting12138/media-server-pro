package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.track.ITrack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One RTSP subscriber session that receives media for a published stream.
 */
public final class RtspSubscriberSession {

    private final String sessionId;
    private final StreamKey streamKey;
    private final Channel controlChannel;
    private final RtpUdpPacketSender udpPacketSender;
    private final Map<String, ITrack> tracksById = new LinkedHashMap<String, ITrack>();
    private final Map<String, RtspTransport> transportsByTrackId = new LinkedHashMap<String, RtspTransport>();

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
        if (track != null && track.connectionAddress() != null && !track.connectionAddress().isEmpty()) {
            return new InetSocketAddress(track.connectionAddress(), clientPort);
        }
        if (!(controlChannel.remoteAddress() instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress remoteAddress = (InetSocketAddress) controlChannel.remoteAddress();
        return new InetSocketAddress(remoteAddress.getAddress(), clientPort);
    }

    private static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
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
