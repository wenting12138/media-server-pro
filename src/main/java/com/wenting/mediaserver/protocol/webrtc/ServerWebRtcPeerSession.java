package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.publish.MediaSubscriberAdapter;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service-layer wrapper around the generic WebRTC peer connection API.
 *
 * Keeps HTTP signaling and published-stream subscription state outside the
 * browser-style RTCPeerConnection object.
 */
public final class ServerWebRtcPeerSession implements AutoCloseable {

    private final String sessionId;
    private final StreamKey streamKey;
    private final RTCPeerConnection peerConnection;
    private final SessionDatagramIo datagramIo;
    private final MediaSubscriberAdapter subscriberAdapter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile IPublishedStream publishedStream;

    public ServerWebRtcPeerSession(
            String sessionId,
            StreamKey streamKey,
            RTCPeerConnection peerConnection,
            SessionDatagramIo datagramIo
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.peerConnection = Objects.requireNonNull(peerConnection, "peerConnection");
        this.datagramIo = Objects.requireNonNull(datagramIo, "datagramIo");
        this.subscriberAdapter = new WebRtcSubscriberAdapter(this);
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public RTCPeerConnection peerConnection() {
        return peerConnection;
    }

    public SessionDatagramIo datagramIo() {
        return datagramIo;
    }

    public MediaSubscriberAdapter subscriberAdapter() {
        return subscriberAdapter;
    }

    public void attachPublishedStream(IPublishedStream stream) {
        if (stream == null) {
            return;
        }
        this.publishedStream = stream;
        stream.addSubscriber(subscriberAdapter);
    }

    public boolean acceptsTrack(String trackId) {
        return true;
    }

    public void writeMediaPacket(InboundRtpPacket packet) {
    }

    public void writeInboundFrame(InboundMediaFrame frame) {
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        datagramIo.receive(data, remoteAddress);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IPublishedStream stream = publishedStream;
        if (stream != null) {
            stream.removeSubscriber(sessionId);
        }
        peerConnection.close();
        datagramIo.close();
    }
}
