package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.MediaPacketTransport;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.IPublishedStream;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.InboundRtpPacket;
import com.wenting.mediaserver.core.registry.StreamRegistry;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCPeerConnection;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpReceiver;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.ingest.WebRtcIngestFeedbackController;
import com.wenting.mediaserver.protocol.webrtc.transport.SessionDatagramIo;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side WebRTC publish session that forwards inbound SRTP media into a published stream.
 */
public final class WebRtcPublishPeerSession implements AutoCloseable {

    private static final long FEEDBACK_TICK_INTERVAL_MS = 50L;

    private final String sessionId;
    private final StreamKey streamKey;
    private final RTCPeerConnection peerConnection;
    private final SessionDatagramIo datagramIo;
    private final StreamRegistry registry;
    private final WebRtcIngestFeedbackController feedbackController;
    private final ScheduledExecutorService feedbackExecutor;
    private final AtomicBoolean lifecycleCleanupInstalled = new AtomicBoolean(false);
    private final RTCPeerConnection.ListenerSubscription rtcpPacketSubscription;
    private final AtomicBoolean ingestActivated = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile IPublishedStream publishedStream;
    private volatile InetSocketAddress remoteAddress;
    private volatile RTCPeerConnection.ListenerSubscription connectionStateSubscription;
    private volatile RTCPeerConnection.ListenerSubscription iceStateSubscription;

    public WebRtcPublishPeerSession(
            String sessionId,
            StreamKey streamKey,
            RTCPeerConnection peerConnection,
            SessionDatagramIo datagramIo,
            StreamRegistry registry
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.peerConnection = Objects.requireNonNull(peerConnection, "peerConnection");
        this.datagramIo = Objects.requireNonNull(datagramIo, "datagramIo");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.feedbackController = new WebRtcIngestFeedbackController(peerConnection);
        this.feedbackExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pc-rr-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        bindReceivers();
        this.rtcpPacketSubscription = bindRtcpListener();
        feedbackExecutor.scheduleAtFixedRate(
                feedbackController::runPeriodicTasks,
                FEEDBACK_TICK_INTERVAL_MS,
                FEEDBACK_TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
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

    public void attachPublishedStream(IPublishedStream stream) {
        this.publishedStream = stream;
    }

    public void activateIngest() {
        if (closed.get()) {
            return;
        }
        ingestActivated.set(true);
    }

    public void installLifecycleCleanup(Runnable cleanupAction) {
        if (cleanupAction == null || !lifecycleCleanupInstalled.compareAndSet(false, true)) {
            return;
        }
        connectionStateSubscription = peerConnection.addConnectionStateListener(state -> {
            if (state == RTCPeerConnection.ConnectionState.CONNECTED) {
                activateIngest();
            }
            if (state == RTCPeerConnection.ConnectionState.FAILED
                    || state == RTCPeerConnection.ConnectionState.CLOSED) {
                runCleanupAction(cleanupAction);
            }
        });
        iceStateSubscription = peerConnection.addIceConnectionStateListener(state -> {
            if (state == RTCPeerConnection.IceConnectionState.FAILED
                    || state == RTCPeerConnection.IceConnectionState.CLOSED) {
                runCleanupAction(cleanupAction);
            }
        });
        if (peerConnection.getConnectionState() == RTCPeerConnection.ConnectionState.CONNECTED) {
            activateIngest();
        }
    }

    public void receive(byte[] data, InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            this.remoteAddress = remoteAddress;
        }
        datagramIo.receive(data, remoteAddress);
    }

    public boolean requestVideoKeyFrame(String trackId) {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (!canReceive(transceiver) || transceiver.getKind() != MediaStreamTrack.Kind.VIDEO) {
                continue;
            }
            String candidateTrackId = trackId(transceiver, transceiver.getReceiver());
            if (trackId != null && !trackId.trim().isEmpty() && !trackId.trim().equals(candidateTrackId)) {
                continue;
            }
            RTCRtpReceiver receiver = transceiver.getReceiver();
            long mediaSsrc = receiver == null ? 0L : receiver.getPeerSsrc();
            if (mediaSsrc <= 0L) {
                continue;
            }
            return peerConnection.sendPictureLossIndication(
                    transceiver.getSender().getSsrc(),
                    mediaSsrc
            );
        }
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeSubscription(connectionStateSubscription);
        closeSubscription(iceStateSubscription);
        IPublishedStream stream = publishedStream;
        if (stream != null && registry.findPublishedStream(streamKey) == stream) {
            registry.removePublishedStream(streamKey);
        }
        rtcpPacketSubscription.close();
        feedbackExecutor.shutdownNow();
        peerConnection.close();
        datagramIo.close();
    }

    private void bindReceivers() {
        for (RTCRtpTransceiver transceiver : peerConnection.getTransceivers()) {
            if (!canReceive(transceiver)) {
                continue;
            }
            RTCRtpReceiver receiver = transceiver.getReceiver();
            if (receiver == null) {
                continue;
            }
            feedbackController.registerTransceiver(transceiver);
            receiver.setOnPacket(rtpPacket -> handleInboundRtp(transceiver, receiver, rtpPacket));
        }
    }

    private RTCPeerConnection.ListenerSubscription bindRtcpListener() {
        return peerConnection.addRtcpPacketListener(packet -> {
            feedbackController.onRtcpPacket(packet);
        });
    }

    private void runCleanupAction(Runnable cleanupAction) {
        try {
            cleanupAction.run();
        } catch (RuntimeException e) {
            // publish teardown should not break connection threads
        }
    }

    private void handleInboundRtp(RTCRtpTransceiver transceiver, RTCRtpReceiver receiver, RtpPacket rtpPacket) {
        if (closed.get() || transceiver == null || receiver == null || rtpPacket == null) {
            return;
        }
        if (!ingestActivated.get()) {
            return;
        }
        if (!canReceive(transceiver)) {
            return;
        }
        IPublishedStream stream = publishedStream;
        if (stream == null) {
            return;
        }
        feedbackController.onRtpPacket(transceiver, rtpPacket);
        TrackType trackType = toTrackType(transceiver.getKind());
        CodecType codecType = transceiver.getNegotiatedCodecType() == null
                ? CodecType.UNKNOWN
                : transceiver.getNegotiatedCodecType();
        InboundMediaFrame frame = new InboundMediaFrame(
                StreamProtocol.WEBRTC,
                trackType,
                codecType,
                sessionId,
                streamKey,
                trackId(transceiver, receiver),
                null,
                null,
                false,
                false,
                remoteAddress,
                rtpPacket.encode()
        );
        int clockRate = transceiver.getNegotiatedClockRate() == null ? defaultClockRate(trackType, codecType) : transceiver.getNegotiatedClockRate().intValue();
        stream.onInboundRtpPacket(new InboundRtpPacket(
                frame,
                clockRate,
                false,
                MediaPacketTransport.UDP,
                null,
                null
        ));
    }

    private boolean canReceive(RTCRtpTransceiver transceiver) {
        if (transceiver == null
                || transceiver.getReceiver() == null
                || (transceiver.getDirection() != RTCRtpTransceiver.Direction.RECVONLY
                && transceiver.getDirection() != RTCRtpTransceiver.Direction.SENDRECV)) {
            return false;
        }
        if (transceiver.getKind() == MediaStreamTrack.Kind.VIDEO) {
            return transceiver.getNegotiatedCodecType() == CodecType.H264;
        }
        if (transceiver.getKind() == MediaStreamTrack.Kind.AUDIO) {
            return transceiver.getNegotiatedCodecType() == CodecType.OPUS
                    || transceiver.getNegotiatedCodecType() == CodecType.G711A
                    || transceiver.getNegotiatedCodecType() == CodecType.G711U;
        }
        return false;
    }

    private TrackType toTrackType(MediaStreamTrack.Kind kind) {
        if (kind == MediaStreamTrack.Kind.VIDEO) {
            return TrackType.VIDEO;
        }
        if (kind == MediaStreamTrack.Kind.AUDIO) {
            return TrackType.AUDIO;
        }
        return TrackType.UNKNOWN;
    }

    private int defaultClockRate(TrackType trackType, CodecType codecType) {
        if (trackType == TrackType.VIDEO) {
            return 90000;
        }
        if (codecType == CodecType.G711A || codecType == CodecType.G711U) {
            return 8000;
        }
        return 48000;
    }

    private String trackId(RTCRtpTransceiver transceiver, RTCRtpReceiver receiver) {
        String mid = transceiver.getMid();
        if (mid != null && !mid.trim().isEmpty()) {
            return receiver.getTrack().getKind() == MediaStreamTrack.Kind.AUDIO
                    ? "audio-" + mid.trim()
                    : "video-" + mid.trim();
        }
        return receiver.getTrack() == null ? "" : receiver.getTrack().getId();
    }

    private void closeSubscription(RTCPeerConnection.ListenerSubscription subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (RuntimeException ignore) {
            // ignore listener shutdown races during session close
        }
    }
}
