package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.protocol.webrtc.WebRtcUdpBootstrap;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.DtlsHandshake;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.UdpDatagramTransport;
import com.wenting.mediaserver.protocol.webrtc.core.ice.*;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpAssociation;
import org.bouncycastle.tls.DTLSTransport;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.DataChannel;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpTransport;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.*;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.MediaDescription;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpException;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants.*;

/**
 * WebRTC RTCPeerConnection (JS-compatible API).
 *
 * Orchestrates ICE → DTLS → SCTP/DataChannel pipeline.
 *
 * Usage:
 *   RTCPeerConnection pc = new RTCPeerConnection();
 *   pc.onIceCandidate = candidate -> signaling.send(candidate);
 *   pc.onDataChannel = dc -> dc.setMessageHandler(...);
 *
 *   RTCSessionDescription offer = pc.createOffer().get();
 *   signaling.send(offer);
 *   pc.setLocalDescription(offer);
 *   ...
 */
public class RTCPeerConnection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RTCPeerConnection.class);

    // ---- JS-compatible state enums ----
    public enum SignalingState { STABLE, HAVE_LOCAL_OFFER, HAVE_REMOTE_OFFER, CLOSED }
    public enum IceConnectionState { NEW, CHECKING, CONNECTED, COMPLETED, FAILED, CLOSED }
    public enum ConnectionState { NEW, CONNECTING, CONNECTED, FAILED, CLOSED }

    // ---- Event callbacks (JS-style) ----
    public volatile Consumer<RTCIceCandidate> onIceCandidate;
    public volatile Consumer<DataChannel> onDataChannel;
    public volatile Consumer<RTCRtpReceiver> ontrack;
    public volatile Consumer<IceConnectionState> onIceConnectionStateChange;
    public volatile Consumer<ConnectionState> onConnectionStateChange;

    // ---- Internal components ----
    private final DatagramIo transport;
    private final boolean ownTransport;
    private IceAgent iceAgent;
    private volatile DtlsHandshake dtlsHandshake;
    private volatile SctpTransport sctpTransport;
    private final List<DataChannel> dataChannels = new CopyOnWriteArrayList<>();
    private final List<DataChannel> pendingDataChannels = new CopyOnWriteArrayList<>();

    // DTLS receive queue — populated by dispatch handler, consumed by DTLS
    private final LinkedBlockingQueue<byte[]> dtlsReceiveQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean firstDtlsPacketLogged = new AtomicBoolean(false);

    // ---- Media / SRTP ----
    private final List<RTCRtpTransceiver> transceivers = new CopyOnWriteArrayList<>();
    private final AtomicLong nextSsrc = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Consumer<byte[]>> ssrcHandlers = new ConcurrentHashMap<>();
    private volatile boolean srtpActive = false;
    private volatile InetSocketAddress remoteAddress;

    // ---- ICE credentials ----
    private String localUfrag;
    private String localPwd;
    private String remoteUfrag;
    private String remotePwd;

    // ---- DTLS certificate (pre-generated) ----
    private final DtlsHandshake.CertCredentials certCredentials;
    private final String localFingerprint;
    private String remoteFingerprint;

    // ---- State ----
    private volatile SignalingState signalingState = SignalingState.STABLE;
    private volatile IceConnectionState iceConnectionState = IceConnectionState.NEW;
    private volatile ConnectionState connectionState = ConnectionState.NEW;
    private boolean iceStarted = false;
    private boolean dtlsStarted = false;
    private boolean remoteDescriptionSet = false;

    // ---- SDP ----
    private RTCSessionDescription localDescription;
    private SdpDescription remoteSdp;

    // ---- DataChannel ID counter (per-connection) ----
    private final AtomicInteger nextDataChannelId = new AtomicInteger(0);

    // ---- 连接监控 ----
    private static final long DTLS_HANDSHAKE_TIMEOUT_MS = 30000;
    private static final long CONNECTION_MONITOR_INTERVAL_MS = 10000;
    private volatile boolean connectionMonitorStarted = false;
    private final ScheduledExecutorService connectionMonitor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pc-monitor");
            t.setDaemon(true);
            return t;
        });

    // ========================================================================
    // Constructor
    // ========================================================================

    public RTCPeerConnection() throws RTCPeerConnectionException {
        this(createOwnedTransport(), true);
    }

    /**
     * Constructor for integrating with a server-managed UDP listener.
     */
    public RTCPeerConnection(DatagramIo transport) throws RTCPeerConnectionException {
        this(transport, false);
    }

    private RTCPeerConnection(DatagramIo transport, boolean ownTransport) throws RTCPeerConnectionException {
        this.transport = transport;
        this.ownTransport = ownTransport;
        if (this.transport == null) {
            throw new RTCPeerConnectionException("Datagram transport must not be null");
        }
        try {
            this.transport.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RTCPeerConnectionException("Failed to start UDP transport", e);
        }

        // Generate ICE credentials
        SecureRandom random = new SecureRandom();
        this.localUfrag = generateCredential(random, 4);
        this.localPwd = generateCredential(random, 22);

        // Pre-generate DTLS certificate + fingerprint
        try {
            SecureRandom sr = new SecureRandom();
            org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto crypto =
                new org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto(sr);
            this.certCredentials = DtlsHandshake.generateSelfSignedCert(crypto);
            this.localFingerprint = DtlsHandshake.computeFingerprint(certCredentials);
        } catch (Exception e) {
            close();
            throw new RTCPeerConnectionException("Failed to generate DTLS certificate", e);
        }

        // Set up STUN/DTLS demux handler
        setupPacketHandler();
    }

    private static DatagramIo createOwnedTransport() {
        return new UdpTransport(0);
    }

    // ========================================================================
    // Public API — SDP
    // ========================================================================

    /**
     * Create an SDP offer (ICE CONTROLLING role).
     */
    public CompletableFuture<RTCSessionDescription> createOffer() {
        if (signalingState == SignalingState.CLOSED) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(new RTCPeerConnectionException("Connection is closed"));
            return f;
        }
        try {
            IceAgent agent = createIceAgent(IceAgent.Role.CONTROLLING);
            String sdp = buildSdp("offer", agent);
            return CompletableFuture.completedFuture(new RTCSessionDescription("offer", sdp));
        } catch (Exception e) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    /**
     * Create an SDP answer (ICE CONTROLLED role).
     */
    public CompletableFuture<RTCSessionDescription> createAnswer() {
        if (signalingState == SignalingState.CLOSED) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(new RTCPeerConnectionException("Connection is closed"));
            return f;
        }
        try {
            IceAgent agent = createIceAgent(IceAgent.Role.CONTROLLED);
            String sdp = buildSdp("answer", agent);
            return CompletableFuture.completedFuture(new RTCSessionDescription("answer", sdp));
        } catch (Exception e) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    /**
     * Store local description and trigger ICE if remote is ready.
     */
    public void setLocalDescription(RTCSessionDescription desc) {
        this.localDescription = desc;
        this.signalingState = "offer".equals(desc.getType())
            ? SignalingState.HAVE_LOCAL_OFFER
            : SignalingState.STABLE;
    }

    /**
     * Parse remote description and configure ICE/DTLS.
     */
    public void setRemoteDescription(RTCSessionDescription desc) throws RTCPeerConnectionException {
        try {
            SdpDescription parsed = SdpParser.parse(desc.getSdp());
            this.remoteSdp = parsed;

            this.remoteUfrag = parsed.getIceUfrag();
            this.remotePwd = parsed.getIcePwd();
            this.remoteFingerprint = parsed.getFingerprint();

            if (this.remoteUfrag == null || this.remotePwd == null) {
                throw new RTCPeerConnectionException("Remote SDP missing ICE credentials");
            }

            this.signalingState = "offer".equals(desc.getType())
                ? SignalingState.HAVE_REMOTE_OFFER
                : SignalingState.STABLE;

            this.remoteDescriptionSet = true;
            this.remoteFingerprint = parsed.getFingerprint();

            // Parse media sections from remote SDP
            boolean isOffer = "offer".equals(desc.getType());
            for (MediaDescription md : parsed.getMediaDescriptions()) {
                if ("audio".equals(md.mediaType) || "video".equals(md.mediaType)) {
                    handleRemoteMediaSection(md, isOffer);
                }
            }
        } catch (Exception e) {
            if (e instanceof RTCPeerConnectionException) throw (RTCPeerConnectionException) e;
            throw new RTCPeerConnectionException("Failed to parse remote description", e);
        }
    }

    private void handleRemoteMediaSection(MediaDescription md, boolean isOffer) {
        String mid = md.getMid();
        if (mid == null) return;

        MediaStreamTrack.Kind kind = "audio".equals(md.mediaType)
            ? MediaStreamTrack.Kind.AUDIO : MediaStreamTrack.Kind.VIDEO;
        Long remoteSsrc = md.getSsrc();

        RTCRtpTransceiver.Direction remoteDirection = extractDirection(md);

        if (isOffer) {
            // Create receiver + sender (sender with local SSRC)
            MediaStreamTrack recvTrack = new MediaStreamTrack(kind, "remote-" + kind.name().toLowerCase() + "-" + mid);
            RTCRtpReceiver receiver = new RTCRtpReceiver(recvTrack);
            if (remoteSsrc != null) {
                receiver.setPeerSsrc(remoteSsrc);
            }

            RTCRtpSender sender = new RTCRtpSender(null, nextSsrc.getAndIncrement());
            RTCRtpTransceiver transceiver = new RTCRtpTransceiver(mid, kind, sender, receiver);

            // Set direction based on remote's direction (we start as recvonly)
            transceiver.setDirection(invertDirection(remoteDirection));

            transceivers.add(transceiver);
            LOG.info("Created " + kind + " transceiver mid=" + mid + " remote-ssrc=" + remoteSsrc);

            // Fire ontrack if remote side is sending
            if (remoteDirection == RTCRtpTransceiver.Direction.SENDRECV
                || remoteDirection == RTCRtpTransceiver.Direction.SENDONLY) {
                fireOnTrack(receiver);
            }
        } else {
            // Answer: update existing transceiver's peer SSRC, or create new if not found
            RTCRtpTransceiver existing = null;
            for (RTCRtpTransceiver t : transceivers) {
                if (mid.equals(t.getMid())) {
                    existing = t;
                    break;
                }
            }

            if (existing != null) {
                if (remoteSsrc != null) {
                    existing.getReceiver().setPeerSsrc(remoteSsrc);
                    LOG.info("Updated peer SSRC for mid=" + mid + " -> " + remoteSsrc);
                }
            } else {
                // Answer introduced a new media section (answerer added a track)
                MediaStreamTrack recvTrack = new MediaStreamTrack(kind, "remote-" + kind.name().toLowerCase() + "-" + mid);
                RTCRtpReceiver receiver = new RTCRtpReceiver(recvTrack);
                if (remoteSsrc != null) {
                    receiver.setPeerSsrc(remoteSsrc);
                }
                RTCRtpSender sender = new RTCRtpSender(null, nextSsrc.getAndIncrement());
                RTCRtpTransceiver transceiver = new RTCRtpTransceiver(mid, kind, sender, receiver);
                transceiver.setDirection(invertDirection(remoteDirection));
                transceivers.add(transceiver);
                LOG.info("Created " + kind + " transceiver from answer mid=" + mid + " remote-ssrc=" + remoteSsrc);

                if (remoteDirection == RTCRtpTransceiver.Direction.SENDRECV
                    || remoteDirection == RTCRtpTransceiver.Direction.SENDONLY) {
                    fireOnTrack(receiver);
                }
            }
        }
    }

    private static RTCRtpTransceiver.Direction extractDirection(MediaDescription md) {
        for (SdpDescription.Attribute attr : md.attributes) {
            if (attr.value == null) {
                if ("sendrecv".equals(attr.key)) return RTCRtpTransceiver.Direction.SENDRECV;
                if ("sendonly".equals(attr.key)) return RTCRtpTransceiver.Direction.SENDONLY;
                if ("recvonly".equals(attr.key)) return RTCRtpTransceiver.Direction.RECVONLY;
                if ("inactive".equals(attr.key)) return RTCRtpTransceiver.Direction.INACTIVE;
            }
        }
        return RTCRtpTransceiver.Direction.RECVONLY;
    }

    private static RTCRtpTransceiver.Direction invertDirection(RTCRtpTransceiver.Direction dir) {
        switch (dir) {
            case SENDONLY: return RTCRtpTransceiver.Direction.RECVONLY;
            case RECVONLY: return RTCRtpTransceiver.Direction.SENDONLY;
            case SENDRECV: return RTCRtpTransceiver.Direction.RECVONLY;
            default: return RTCRtpTransceiver.Direction.INACTIVE;
        }
    }

    // ========================================================================
    // Public API — ICE
    // ========================================================================

    /**
     * Add a remote ICE candidate. Starts connectivity checks when the first
     * candidate is added after both local and remote descriptions are set.
     */
    public synchronized void addIceCandidate(RTCIceCandidate candidate)
            throws RTCPeerConnectionException {
        if (iceAgent == null) {
            throw new RTCPeerConnectionException("ICE agent not initialized (call createOffer/createAnswer first)");
        }

        IceCandidate parsed = parseCandidate(candidate.getCandidate(), remoteUfrag);
        if (parsed != null) {
            iceAgent.addRemoteCandidate(parsed);
        }

        // Start ICE checks once we have remote candidates AND gathering is complete
        if (!iceStarted && remoteDescriptionSet && iceAgent.isGatheringComplete()) {
            iceStarted = true;
            iceAgent.startConnectivityChecks();
        }
    }

    // ========================================================================
    // Public API — DataChannel
    // ========================================================================

    /**
     * Create a DataChannel. Opens immediately if SCTP is ready, or queues it
     * until the SCTP association is established.
     */
    public DataChannel createDataChannel(String label) {
        DataChannel dc = new DataChannel(null, nextDataChannelId.getAndIncrement(),
            label, DATA_CHANNEL_RELIABLE_UNORDERED, 0);
        dataChannels.add(dc);

        if (sctpTransport != null) {
            dc.setTransport(sctpTransport);
            openDataChannel(dc);
        } else {
            pendingDataChannels.add(dc);
        }

        return dc;
    }

    // ---- Media API ----

    /**
     * Add a media track to this connection. Creates or reuses an RTCRtpTransceiver
     * for the track, following the JS WebRTC API behavior.
     *
     * If there is an existing transceiver of the same kind with no sender track
     * and direction RECVONLY (created from remote SDP), it is reused.
     * Otherwise a new transceiver is created.
     *
     * @param track the media track to send
     * @return the new RTCRtpTransceiver
     */
    public RTCRtpTransceiver addTrack(MediaStreamTrack track) {
        // Try to reuse an existing recvonly transceiver of the same kind
        for (RTCRtpTransceiver t : transceivers) {
            if (t.getKind() == track.getKind()
                && t.getSender().getTrack() == null
                && (t.getDirection() == RTCRtpTransceiver.Direction.RECVONLY
                    || t.getDirection() == RTCRtpTransceiver.Direction.SENDRECV)) {
                t.getSender().replaceTrack(track);
                t.setDirection(RTCRtpTransceiver.Direction.SENDRECV);
                LOG.info("Reused existing transceiver for track: " + track + " mid=" + t.getMid());
                return t;
            }
        }

        // No match: create a new transceiver
        long ssrc = nextSsrc.getAndIncrement();
        RTCRtpSender sender = new RTCRtpSender(track, ssrc);
        RTCRtpReceiver receiver = new RTCRtpReceiver(
            new MediaStreamTrack(track.getKind(), "recv-" + track.getId()));
        String mid = String.valueOf(transceivers.size() + 1);
        RTCRtpTransceiver transceiver = new RTCRtpTransceiver(mid, track.getKind(), sender, receiver);
        transceivers.add(transceiver);
        LOG.info("Added track: " + track + " ssrc=" + ssrc + " mid=" + mid);
        return transceiver;
    }

    /**
     * Returns the list of RTCRtpTransceivers currently associated with this connection.
     */
    public List<RTCRtpTransceiver> getTransceivers() {
        return Collections.unmodifiableList(transceivers);
    }

    /**
     * Send SRTP-encrypted data to the remote peer.
     * The remote address is determined from the ICE nominated pair.
     */
    public void sendSrtpPacket(byte[] srtpData) {
        InetSocketAddress addr = remoteAddress;
        if (addr != null && srtpData != null) {
            transport.send(srtpData, addr).exceptionally(ex -> {
                LOG.warn("Failed to send SRTP packet: " + ex.getMessage());
                return null;
            });
        }
    }

    // ========================================================================
    // Public API — Lifecycle
    // ========================================================================

    @Override
    public synchronized void close() {
        setIceState(IceConnectionState.CLOSED);
        signalingState = SignalingState.CLOSED;
        setConnectionState(ConnectionState.CLOSED);

        if (sctpTransport != null) {
            try { sctpTransport.close(); } catch (Exception e) { /* ignore */ }
            sctpTransport = null;
        }
        if (dtlsHandshake != null) {
            dtlsHandshake.close();
            dtlsHandshake = null;
        }
        if (iceAgent != null) {
            iceAgent.shutdown();
        }
        connectionMonitor.shutdownNow();
        ssrcHandlers.clear();
        transceivers.clear();
        if (ownTransport) {
            transport.close(3, TimeUnit.SECONDS);
        }
    }

    /**
     * 主动关闭连接（线程安全）。可以多次调用。
     */
    public synchronized void closeGracefully(long timeoutMs) {
        LOG.info("Closing connection gracefully (timeout=" + timeoutMs + "ms)");
        setIceState(IceConnectionState.CLOSED);
        signalingState = SignalingState.CLOSED;
        setConnectionState(ConnectionState.CLOSED);

        // 先停止连接监控
        connectionMonitor.shutdownNow();

        // 关闭 SCTP
        if (sctpTransport != null) {
            try { sctpTransport.close(); } catch (Exception e) { /* ignore */ }
            sctpTransport = null;
        }

        // 关闭 DTLS
        if (dtlsHandshake != null) {
            dtlsHandshake.close();
            dtlsHandshake = null;
        }

        // 关闭 ICE
        if (iceAgent != null) {
            iceAgent.shutdown();
        }

        ssrcHandlers.clear();
        transceivers.clear();

        // 关闭 UDP 传输（带超时）
        if (ownTransport) {
            transport.close(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Restart ICE on this connection (RFC 5245 Section 9.2).
     * Clears existing state, generates new credentials, triggers re-gathering.
     * Returns a new offer SDP with updated ICE info.
     */
    public synchronized CompletableFuture<RTCSessionDescription> restartIce() {
        if (signalingState == SignalingState.CLOSED) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(new RTCPeerConnectionException("Connection is closed"));
            return f;
        }

        if (iceAgent == null) {
            CompletableFuture<RTCSessionDescription> f = new CompletableFuture<>();
            f.completeExceptionally(new RTCPeerConnectionException("ICE not initialized"));
            return f;
        }

        // Generate new credentials
        SecureRandom random = new SecureRandom();
        this.localUfrag = generateCredential(random, 4);
        this.localPwd = generateCredential(random, 22);

        // Restart the ICE agent (clears state, returns to NEW)
        iceAgent.restartIce();

        // Reset flags
        iceStarted = false;
        dtlsStarted = false;
        connectionMonitorStarted = false;
        signalingState = SignalingState.STABLE;

        // Re-gather local candidates
        InetSocketAddress localAddr = transport.getLocalAddress();
        CandidateGatherer gatherer = new CandidateGatherer(localAddr.getPort());
        try {
            List<IceCandidate> hostCandidates = gatherer.gatherHostCandidates();
            if (hostCandidates.isEmpty()) {
                String host = localAddr.getAddress().isAnyLocalAddress()
                    ? "127.0.0.1" : localAddr.getHostString();
                hostCandidates = Collections.singletonList(new IceCandidate("1", 1,
                    new InetSocketAddress(host, localAddr.getPort()), CandidateType.HOST));
            }
            iceAgent.addLocalCandidates(hostCandidates);
        } catch (SocketException e) {
            LOG.warn("Failed to gather candidates after restart: " + e.getMessage());
        }

        iceAgent.startGathering();

        // Generate new SDP offer
        String sdp = buildSdp("offer", iceAgent);
        RTCSessionDescription newOffer = new RTCSessionDescription("offer", sdp);
        return CompletableFuture.completedFuture(newOffer);
    }

    public SignalingState getSignalingState() { return signalingState; }
    public IceConnectionState getIceConnectionState() { return iceConnectionState; }
    public ConnectionState getConnectionState() { return connectionState; }
    public String getLocalUfrag() { return localUfrag; }
    public String getLocalPwd() { return localPwd; }
    public String getLocalFingerprint() { return localFingerprint; }
    public RTCSessionDescription getLocalDescription() { return localDescription; }

    // ========================================================================
    // Internal — ICE Agent creation
    // ========================================================================

    private synchronized IceAgent createIceAgent(IceAgent.Role role) {
        if (iceAgent != null) return iceAgent;

        this.iceAgent = new IceAgent(transport, role);
        iceAgent.setCredentials(localUfrag, localPwd);

        // Gather local HOST candidates from network interfaces
        InetSocketAddress localAddr = transport.getLocalAddress();
        CandidateGatherer gatherer = new CandidateGatherer(localAddr.getPort());
        try {
            List<IceCandidate> hostCandidates = gatherer.gatherHostCandidates();
            if (hostCandidates.isEmpty()) {
                // Fallback: use the bound address
                String host = localAddr.getAddress().isAnyLocalAddress()
                    ? "127.0.0.1" : localAddr.getHostString();
                hostCandidates = Collections.singletonList(new IceCandidate("1", 1,
                    new InetSocketAddress(host, localAddr.getPort()), CandidateType.HOST));
            }
            iceAgent.addLocalCandidates(hostCandidates);
        } catch (SocketException e) {
            LOG.warn("Failed to gather candidates: " + e.getMessage());
            String host = localAddr.getAddress().isAnyLocalAddress()
                ? "127.0.0.1" : localAddr.getHostString();
            IceCandidate fallback = new IceCandidate("1", 1,
                new InetSocketAddress(host, localAddr.getPort()), CandidateType.HOST);
            iceAgent.addLocalCandidates(Collections.singletonList(fallback));
        }

        // STUN servers for srflx: not set by default.
        // Call iceAgent.setStunServers() to enable server-reflexive candidates.

        // Listen for ICE events
        iceAgent.addEventListener(event -> {
            switch (event.getType()) {
                case CANDIDATE_GATHERED:
                    if (event.getCandidate() != null) {
                        fireIceCandidate(event.getCandidate());
                    }
                    break;
                case PAIR_SUCCEEDED:
                    onIcePairSucceeded(event.getPair());
                    break;
                case NOMINATED:
                    LOG.info("ICE pair nominated: " + event.getPair());
                    break;
                case STATE_CHANGED:
                    onIceStateChanged();
                    break;
                default:
                    break;
            }
        });

        // Start gathering (host already present, srflx begins now)
        iceAgent.startGathering();

        return iceAgent;
    }

    private void fireIceCandidate(IceCandidate c) {
        Consumer<RTCIceCandidate> handler = onIceCandidate;
        if (handler != null) {
            handler.accept(new RTCIceCandidate(c.toSdpAttribute(), "0", 0));
        }
    }

    private static List<InetSocketAddress> getDefaultStunServers() {
        List<InetSocketAddress> servers = new ArrayList<>();
        servers.add(new InetSocketAddress("stun.l.google.com", 19302));
        servers.add(new InetSocketAddress("stun.cloudflare.com", 3478));
        return servers;
    }

    // ========================================================================
    // Internal — ICE event handlers
    // ========================================================================

    private synchronized void onIcePairSucceeded(CandidatePair pair) {
        if (iceConnectionState == IceConnectionState.CONNECTED
            || iceConnectionState == IceConnectionState.CLOSED) return;

        this.remoteAddress = pair.getRemote().getAddress();
        setIceState(IceConnectionState.CONNECTED);
        startDtls(pair);
    }

    private synchronized void onIceStateChanged() {
        if (iceAgent == null) return;
        switch (iceAgent.getState()) {
            case FAILED:
                setIceState(IceConnectionState.FAILED);
                setConnectionState(ConnectionState.FAILED);
                break;
            case CONNECTED:
                // Already handled via PAIR_SUCCEEDED
                break;
            case COMPLETED:
                setIceState(IceConnectionState.COMPLETED);
                LOG.info("ICE completed, selected pair: " + iceAgent.getSelectedPair());
                break;
            default:
                break;
        }
    }

    private void setIceState(IceConnectionState newState) {
        IceConnectionState old = iceConnectionState;
        iceConnectionState = newState;
        if (old != newState) {
            Consumer<IceConnectionState> handler = onIceConnectionStateChange;
            if (handler != null) {
                handler.accept(newState);
            }
        }
    }

    private void setConnectionState(ConnectionState newState) {
        ConnectionState old = connectionState;
        connectionState = newState;
        if (old != newState) {
            Consumer<ConnectionState> handler = onConnectionStateChange;
            if (handler != null) {
                handler.accept(newState);
            }
        }
    }

    // ========================================================================
    // Internal — DTLS handshake
    // ========================================================================

    private synchronized void startDtls(CandidatePair pair) {
        if (dtlsStarted) return;
        dtlsStarted = true;

        InetSocketAddress remoteAddr = pair.getRemote().getAddress();

        // Determine DTLS roles: offerer (CONTROLLING) = DTLS client
        boolean isDtlsServer = iceAgent.getRole() == IceAgent.Role.CONTROLLED;

        setConnectionState(ConnectionState.CONNECTING);

        Thread dtlsThread = new Thread(() -> {
            try {
                if (iceConnectionState == IceConnectionState.CLOSED) return;

                // Create DTLS transport using the shared receive queue
                UdpDatagramTransport dtlsTransport = new UdpDatagramTransport(
                    transport, remoteAddr, dtlsReceiveQueue);

                // 带超时的 DTLS 握手
                DtlsHandshake handshake = DtlsHandshake.handshakeWithTimeout(
                    dtlsTransport, isDtlsServer, certCredentials,
                    DTLS_HANDSHAKE_TIMEOUT_MS);
                this.dtlsHandshake = handshake;

                LOG.info("DTLS handshake complete");

                // After DTLS handshake, create SRTP contexts for media transceivers
                byte[] keyMaterial = handshake.getSrtpKeyMaterial();
                if (keyMaterial != null && !transceivers.isEmpty()) {
                    srtpActive = true;
                    setupSrtpForTransceivers(keyMaterial, isDtlsServer);
                }

                // Start SCTP over DTLS
                startSctp(handshake.getDtlsTransport());

                // 启动连接健康监控
                startConnectionMonitor();
            } catch (Exception e) {
                LOG.warn("DTLS/SCTP setup failed: " + e.getMessage());
                // 关闭前已经开始的连接则标记失败，否则忽略
                if (iceConnectionState == IceConnectionState.CONNECTED
                    || iceConnectionState == IceConnectionState.COMPLETED) {
                    setConnectionState(ConnectionState.FAILED);
                }
            }
        }, "pc-dtls-" + (isDtlsServer ? "server" : "client"));
        dtlsThread.setDaemon(true);
        dtlsThread.start();
    }

    /**
     * 连接健康监控 — 定期检查 DTLS/SCTP 是否仍活跃。
     * 如果检测到连接断开，触发 ConnectionState.FAILED。
     */
    private synchronized void startConnectionMonitor() {
        if (connectionMonitorStarted) return;
        connectionMonitorStarted = true;

        connectionMonitor.scheduleAtFixedRate(() -> {
            try {
                if (connectionState == ConnectionState.CLOSED
                    || connectionState == ConnectionState.FAILED) {
                    return;
                }

                // 检查 DTLS 传输是否还活着
                if (dtlsHandshake != null && dtlsHandshake.getDtlsTransport() != null) {
                    DTLSTransport dtls = dtlsHandshake.getDtlsTransport();
                    // 尝试发送一个空的 DTLS 应用数据包来检查连接
                    // 如果连接已死，BC 会抛出 IOException
                    try {
                        dtls.send(new byte[0], 0, 0);
                    } catch (IOException e) {
                        // send 失败不一定是连接断开，但持续失败需要处理
                    }
                }

                // 检查 SCTP 状态
                if (sctpTransport != null && sctpTransport.getAssociation() != null) {
                    SctpAssociation.State sctpState =
                        sctpTransport.getAssociation().getState();
                    if (sctpState == SctpAssociation.State.CLOSED
                        || sctpState == SctpAssociation.State.COOKIE_WAIT
                        || sctpState == SctpAssociation.State.COOKIE_ECHOED) {
                        // SCTP 连接已断开但应用层还认为是 CONNECTED
                        LOG.warn("Connection monitor: SCTP state is " + sctpState
                            + ", but connection state is " + connectionState);
                    }
                }
            } catch (Exception e) {
                LOG.error("Connection monitor check failed: " + e.getMessage());
            }
        }, CONNECTION_MONITOR_INTERVAL_MS, CONNECTION_MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

        LOG.info("Connection health monitor started (interval="
            + CONNECTION_MONITOR_INTERVAL_MS + "ms)");
    }

    /**
     * Create SRTP send/receive crypto contexts for all transceivers
     * using key material exported from the DTLS handshake (RFC 5764).
     *
     * Send context uses our write key, receive context uses peer's write key.
     */
    private synchronized void setupSrtpForTransceivers(byte[] keyMaterial, boolean isDtlsServer) {
        // DTLS server ↔ ICE CONTROLLED; DTLS client ↔ ICE CONTROLLING
        // Our send key = our write key
        // Our receive key = peer's write key
        boolean isDtlsClient = !isDtlsServer;

        for (RTCRtpTransceiver transceiver : transceivers) {
            // Send context: our write key
            SrtpCryptoContext sendCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, isDtlsClient);
            transceiver.getSender().setSrtpContext(sendCtx);

            // Receive context: peer's write key
            SrtpCryptoContext recvCtx = SrtpCryptoContext.fromKeyMaterial(keyMaterial, !isDtlsClient);
            transceiver.getReceiver().setSrtpContext(recvCtx);

            // Register SSRC demux handler for receiving
            final RTCRtpReceiver receiver = transceiver.getReceiver();
            final long peerSsrc = receiver.getPeerSsrc();
            if (peerSsrc > 0) {
                ssrcHandlers.put(peerSsrc, (srtpData) -> {
                    try {
                        SrtpTransform transform = new SrtpTransform(recvCtx, peerSsrc);
                        RtpPacket packet = transform.unprotect(srtpData);
                        // Deliver to the receiver's packet handler
                        Consumer<RtpPacket> packetHandler = receiver.getOnPacket();
                        if (packetHandler != null) {
                            packetHandler.accept(packet);
                        }
                    } catch (SrtpException e) {
                        LOG.error("SRTP unprotect failed for SSRC " + peerSsrc + ": " + e.getMessage());
                    }
                });
                LOG.info("Registered SRTP demux for SSRC " + peerSsrc + " (" + transceiver.getKind() + ")");
            }
        }
    }

    private void fireOnTrack(RTCRtpReceiver receiver) {
        Consumer<RTCRtpReceiver> handler = ontrack;
        if (handler != null) {
            handler.accept(receiver);
        }
    }

    // ========================================================================
    // Internal — SCTP + DataChannel
    // ========================================================================

    private void startSctp(DTLSTransport dtlsTransport) {
        boolean isClient = iceAgent.getRole() == IceAgent.Role.CONTROLLING;

        this.sctpTransport = new SctpTransport(dtlsTransport, isClient);
        sctpTransport.setDataHandler(this::onSctpData);

        // Set transport on all already-created DataChannels
        for (DataChannel dc : dataChannels) {
            dc.setTransport(sctpTransport);
        }

        // Open pending DataChannels and signal CONNECTED when SCTP association is ready
        SctpAssociation assoc = sctpTransport.getAssociation();
        assoc.setStateHandler(newState -> {
            if (newState == SctpAssociation.State.ESTABLISHED) {
                for (DataChannel dc : pendingDataChannels) {
                    dc.setTransport(sctpTransport);
                    openDataChannel(dc);
                }
                pendingDataChannels.clear();
                setConnectionState(ConnectionState.CONNECTED);
            }
        });

        // Check if already established (avoid race)
        if (assoc.getState() == SctpAssociation.State.ESTABLISHED) {
            for (DataChannel dc : pendingDataChannels) {
                dc.setTransport(sctpTransport);
                openDataChannel(dc);
            }
            pendingDataChannels.clear();
            setConnectionState(ConnectionState.CONNECTED);
        }
    }

    private void onSctpData(int streamId, long ppid, byte[] data, boolean unordered) {
        if (ppid == PPID_WEBRTC_STRING && data.length > 0) {
            int firstByte = data[0] & 0xFF;
            if (firstByte == DATA_CHANNEL_OPEN) {
                handleIncomingDataChannel(streamId, data);
                return;
            } else if (firstByte == DATA_CHANNEL_ACK) {
                handleDataChannelAck(streamId);
                return;
            }
        }
        // Regular data — route to the matching DataChannel
        for (DataChannel dc : dataChannels) {
            if (dc.getId() == streamId) {
                dc.receiveMessage(data, ppid == PPID_WEBRTC_BINARY);
                break;
            }
        }
    }

    private void handleIncomingDataChannel(int streamId, byte[] data) {
        DataChannel dc = DataChannel.parseOpenMessage(null, data, streamId);
        if (dc == null) return;
        dc.setTransport(sctpTransport);
        dataChannels.add(dc);
        Consumer<DataChannel> handler = onDataChannel;
        if (handler != null) handler.accept(dc);
        try {
            dc.handleOpenMessage(new byte[0]);
        } catch (IOException e) {
            LOG.warn("Failed to ACK DataChannel: " + e.getMessage());
        }
    }

    private void handleDataChannelAck(int streamId) {
        for (DataChannel dc : dataChannels) {
            if (dc.getId() == streamId) {
                dc.handleAck();
                break;
            }
        }
    }

    private void openDataChannel(DataChannel dc) {
        try {
            dc.open();
        } catch (IOException e) {
            LOG.warn("Failed to open DataChannel: " + e.getMessage());
        }
    }

    // ========================================================================
    // Internal — Packet dispatch (STUN vs DTLS)
    // ========================================================================

    private void setupPacketHandler() {
        transport.setPacketHandler((data, remote) -> {
            if (isStunPacket(data)) {
                handleStunPacket(data, remote);
            } else if (isDtlsPacket(data)) {
                if (firstDtlsPacketLogged.compareAndSet(false, true)) {
                    LOG.info("First DTLS packet received from " + remote
                        + " contentType=" + (data[0] & 0xFF)
                        + " bytes=" + data.length);
                }
                dtlsReceiveQueue.offer(data);
            } else if (isRtpPacket(data)) {
                handleRtpPacket(data, remote);
            }
        });
    }

    private static boolean isDtlsPacket(byte[] data) {
        return data.length > 0 && (data[0] & 0xFF) >= 20 && (data[0] & 0xFF) <= 63;
    }

    private static boolean isRtpPacket(byte[] data) {
        // RTP version 2: first byte has bits 7-6 = 10 (0x80-0xBF)
        return data.length >= 12 && (data[0] & 0xC0) == 0x80;
    }

    private void handleRtpPacket(byte[] data, InetSocketAddress remote) {
        if (data.length < 12) return;
        // Extract SSRC from RTP header bytes 8-11
        long ssrc = ((long)(data[8] & 0xFF) << 24)
                  | ((long)(data[9] & 0xFF) << 16)
                  | ((long)(data[10] & 0xFF) << 8)
                  | ((long)(data[11] & 0xFF));
        Consumer<byte[]> handler = ssrcHandlers.get(ssrc);
        if (handler != null) {
            handler.accept(data);
        } else {
            // Might be a DTLS packet that happens to look like RTP (rare)
            // Check if DTLS is still active
            if (data[0] >= 20 && data[0] <= 23) {
                dtlsReceiveQueue.offer(data);
            }
        }
    }

    private static boolean isStunPacket(byte[] data) {
        // STUN magic cookie at bytes 4-7: 0x2112A442
        return data.length >= 20
            && data[4] == 0x21 && data[5] == 0x12
            && data[6] == (byte) 0xA4 && data[7] == 0x42;
    }

    private void handleStunPacket(byte[] data, InetSocketAddress remote) {
        try {
            StunMessage msg = StunMessage.decode(data);
            if (iceAgent != null) {
                iceAgent.handleStunMessage(msg, remote);
            }
        } catch (Exception e) {
            // ignore decode errors
        }
    }

    // ========================================================================
    // Internal — SDP builder
    // ========================================================================

    private String buildSdp(String type, IceAgent agent) {
        if ("answer".equals(type) && remoteSdp != null) {
            return buildAnswerSdp(agent, remoteSdp);
        }
        InetSocketAddress localAddr = transport.getLocalAddress();
        String host = localAddr.getAddress().isAnyLocalAddress()
            ? "127.0.0.1" : localAddr.getHostString();
        SdpBuilder builder = new SdpBuilder();
        builder.setOrigin("-", System.currentTimeMillis(), host);

        // Build BUNDLE mids dynamically: 0=app, 1..N=media
        StringBuilder bundleMids = new StringBuilder("0");
        for (int i = 0; i < transceivers.size(); i++) {
            bundleMids.append(" ").append(i + 1);
        }

        // Session-level attributes
        builder.addSessionAttribute("group", "BUNDLE " + bundleMids);
        builder.addSessionAttribute("ice-ufrag", localUfrag);
        builder.addSessionAttribute("ice-pwd", localPwd);
        builder.addSessionAttribute("fingerprint", "sha-256 " + localFingerprint);
        builder.addSessionAttribute("msid-semantic", " WMS");

        // ===== Application (SCTP/DataChannel) media =====
        SdpBuilder.MediaBuilder appMedia = builder.addMedia("application", 9, "DTLS/SCTP", 5000);
        appMedia.addAttribute("mid", "0");
        appMedia.addAttribute("setup", "actpass");
        addCandidateAttributes(appMedia, agent);
        appMedia.addAttribute("sctp-port", String.valueOf(SctpConstants.DEFAULT_OS));

        // ===== Transceiver media sections =====
        for (int i = 0; i < transceivers.size(); i++) {
            RTCRtpTransceiver trans = transceivers.get(i);
            String mediaType = trans.getKind() == MediaStreamTrack.Kind.AUDIO ? "audio" : "video";
            int pt = trans.getKind() == MediaStreamTrack.Kind.AUDIO ? 111 : 96;
            String codec = trans.getKind() == MediaStreamTrack.Kind.AUDIO
                ? "111 opus/48000/2" : "96 H264/90000";

            SdpBuilder.MediaBuilder media = builder.addMedia(mediaType, 9, "UDP/TLS/RTP/SAVPF",
                trans.getKind() == MediaStreamTrack.Kind.AUDIO ? 111 : 96);
            media.addAttribute("mid", String.valueOf(i + 1));
            media.addAttribute("rtpmap", codec);
            media.addAttribute("rtcp-mux");
            media.addAttribute("setup", "actpass");

            // Direction
            switch (trans.getDirection()) {
                case SENDRECV: media.addAttribute("sendrecv"); break;
                case SENDONLY: media.addAttribute("sendonly"); break;
                case RECVONLY: media.addAttribute("recvonly"); break;
                case INACTIVE: media.addAttribute("inactive"); break;
            }

            // SSRC with cname
            long ssrc = trans.getSender().getSsrc();
            media.addAttribute("ssrc", ssrc + " cname:webrtc-java");

            // MSID
            MediaStreamTrack track = trans.getSender().getTrack();
            if (track != null) {
                media.addAttribute("msid", trans.getMid() + " " + track.getId());
            }

            // ICE candidates for this media section
            addCandidateAttributes(media, agent);
        }

        return builder.build();
    }

    private String buildAnswerSdp(IceAgent agent, SdpDescription offer) {
        InetSocketAddress localAddr = transport.getLocalAddress();
        String host = localAddr.getAddress().isAnyLocalAddress()
            ? "127.0.0.1" : localAddr.getHostString();
        SdpBuilder builder = new SdpBuilder();
        builder.setOrigin("-", System.currentTimeMillis(), host);

        builder.addSessionAttribute("group", "BUNDLE " + answerBundleMids(offer));
        builder.addSessionAttribute("ice-ufrag", localUfrag);
        builder.addSessionAttribute("ice-pwd", localPwd);
        builder.addSessionAttribute("fingerprint", "sha-256 " + localFingerprint);
        builder.addSessionAttribute("msid-semantic", " WMS");

        for (MediaDescription offeredMedia : offer.getMediaDescriptions()) {
            if ("application".equals(offeredMedia.mediaType)) {
                appendApplicationAnswer(builder, offeredMedia, agent);
                continue;
            }
            if ("audio".equals(offeredMedia.mediaType) || "video".equals(offeredMedia.mediaType)) {
                appendMediaAnswer(builder, offeredMedia, agent);
            }
        }

        return builder.build();
    }

    private String answerBundleMids(SdpDescription offer) {
        StringBuilder mids = new StringBuilder();
        for (MediaDescription media : offer.getMediaDescriptions()) {
            String mid = media.getMid();
            if (mid == null || mid.trim().isEmpty()) {
                continue;
            }
            if (mids.length() > 0) {
                mids.append(' ');
            }
            mids.append(mid);
        }
        return mids.length() == 0 ? "0" : mids.toString();
    }

    private void appendApplicationAnswer(SdpBuilder builder, MediaDescription offeredMedia, IceAgent agent) {
        int[] payloadTypes = toPayloadTypeArray(offeredMedia.payloadTypes);
        if (payloadTypes.length == 0) {
            payloadTypes = new int[]{5000};
        }
        SdpBuilder.MediaBuilder appMedia = builder.addMedia(
            offeredMedia.mediaType,
            9,
            offeredMedia.protocol,
            payloadTypes);
        appMedia.addAttribute("mid", safeMid(offeredMedia));
        appMedia.addAttribute("setup", "passive");
        addCandidateAttributes(appMedia, agent);
        appMedia.addAttribute("sctp-port", String.valueOf(SctpConstants.DEFAULT_OS));
    }

    private void appendMediaAnswer(SdpBuilder builder, MediaDescription offeredMedia, IceAgent agent) {
        RTCRtpTransceiver transceiver = findTransceiverByMid(offeredMedia.getMid());
        if (transceiver == null) {
            SdpBuilder.MediaBuilder rejected = builder.addMedia(
                offeredMedia.mediaType,
                0,
                offeredMedia.protocol,
                firstPayloadTypeOrDefault(offeredMedia));
            rejected.addAttribute("mid", safeMid(offeredMedia));
            return;
        }

        int payloadType = answerPayloadType(offeredMedia, transceiver.getKind());
        SdpBuilder.MediaBuilder media = builder.addMedia(offeredMedia.mediaType, 9, offeredMedia.protocol, payloadType);
        media.addAttribute("mid", safeMid(offeredMedia));
        media.addAttribute("rtpmap", answerRtpMap(offeredMedia, payloadType, transceiver.getKind()));
        media.addAttribute("rtcp-mux");
        media.addAttribute("setup", "passive");
        addDirectionAttribute(media, transceiver.getDirection());

        long ssrc = transceiver.getSender().getSsrc();
        media.addAttribute("ssrc", ssrc + " cname:webrtc-java");

        MediaStreamTrack track = transceiver.getSender().getTrack();
        if (track != null) {
            media.addAttribute("msid", safeMid(offeredMedia) + " " + track.getId());
        }

        addCandidateAttributes(media, agent);
    }

    private RTCRtpTransceiver findTransceiverByMid(String mid) {
        if (mid == null) {
            return null;
        }
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver != null && mid.equals(transceiver.getMid())) {
                return transceiver;
            }
        }
        return null;
    }

    private void addDirectionAttribute(SdpBuilder.MediaBuilder media, RTCRtpTransceiver.Direction direction) {
        switch (direction) {
            case SENDRECV: media.addAttribute("sendrecv"); break;
            case SENDONLY: media.addAttribute("sendonly"); break;
            case RECVONLY: media.addAttribute("recvonly"); break;
            case INACTIVE: media.addAttribute("inactive"); break;
            default: media.addAttribute("inactive"); break;
        }
    }

    private int answerPayloadType(MediaDescription offeredMedia, MediaStreamTrack.Kind kind) {
        String codecName = kind == MediaStreamTrack.Kind.AUDIO ? "opus/" : "h264/";
        Integer matched = findPayloadTypeByCodec(offeredMedia, codecName);
        if (matched != null) {
            return matched.intValue();
        }
        return firstPayloadTypeOrDefault(offeredMedia);
    }

    private String answerRtpMap(MediaDescription offeredMedia, int payloadType, MediaStreamTrack.Kind kind) {
        String offered = findRtpMapValue(offeredMedia, payloadType);
        if (offered != null) {
            return offered;
        }
        return kind == MediaStreamTrack.Kind.AUDIO
            ? payloadType + " opus/48000/2"
            : payloadType + " H264/90000";
    }

    private Integer findPayloadTypeByCodec(MediaDescription media, String codecPrefixLowerCase) {
        for (SdpDescription.Attribute attr : media.attributes) {
            if (!"rtpmap".equals(attr.key) || attr.value == null) {
                continue;
            }
            String[] parts = attr.value.trim().split("\\s+", 2);
            if (parts.length < 2 || !parts[1].toLowerCase(Locale.ROOT).startsWith(codecPrefixLowerCase)) {
                continue;
            }
            try {
                return Integer.valueOf(Integer.parseInt(parts[0]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String findRtpMapValue(MediaDescription media, int payloadType) {
        String prefix = String.valueOf(payloadType) + " ";
        for (SdpDescription.Attribute attr : media.attributes) {
            if ("rtpmap".equals(attr.key) && attr.value != null && attr.value.startsWith(prefix)) {
                return attr.value;
            }
        }
        return null;
    }

    private int firstPayloadTypeOrDefault(MediaDescription media) {
        if (!media.payloadTypes.isEmpty()) {
            return media.payloadTypes.get(0).intValue();
        }
        return "audio".equals(media.mediaType) ? 111 : 96;
    }

    private int[] toPayloadTypeArray(List<Integer> payloadTypes) {
        int[] result = new int[payloadTypes == null ? 0 : payloadTypes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = payloadTypes.get(i).intValue();
        }
        return result;
    }

    private String safeMid(MediaDescription media) {
        String mid = media.getMid();
        return mid == null || mid.trim().isEmpty() ? "0" : mid;
    }

    private void addCandidateAttributes(SdpBuilder.MediaBuilder media, IceAgent agent) {
        for (IceCandidate c : agent.getLocalCandidates()) {
            String relatedAddr = null;
            int relatedPort = 0;
            if (c.getRelatedAddress() != null) {
                relatedAddr = c.getRelatedAddress().getHostString();
                relatedPort = c.getRelatedAddress().getPort();
            }
            SdpBuilder.IceCandidateInfo ci = new SdpBuilder.IceCandidateInfo(
                c.getFoundation(), c.getComponentId(), c.getTransport(),
                c.getPriority(), c.getAddress().getHostString(),
                c.getAddress().getPort(), IceCandidate.typeToString(c.getType()),
                relatedAddr, relatedPort);
            media.addCandidate(ci);
        }
    }

    // ========================================================================
    // Internal — Candidate parsing from SDP
    // ========================================================================

    /**
     * Parse an SDP candidate attribute line into an IceCandidate.
     * Input: "a=candidate:foundation componentId transport priority ip port typ type ..."
     */
    public static IceCandidate parseCandidate(String sdpAttr, String ufrag) {
        try {
            String val = sdpAttr;
            if (val.startsWith("a=candidate:")) {
                val = val.substring("a=candidate:".length());
            } else if (val.startsWith("candidate:")) {
                val = val.substring("candidate:".length());
            }

            String[] parts = val.split(" ");
            if (parts.length < 8) return null;

            String foundation = parts[0];
            int componentId = Integer.parseInt(parts[1]);
            String transportType = parts[2];
            long priority = Long.parseLong(parts[3]);
            String ip = parts[4];
            int port = Integer.parseInt(parts[5]);

            // parts[6] should be "typ"
            CandidateType type = IceCandidate.stringToType(parts[7]);

            return new IceCandidate(foundation, componentId, transportType,
                new InetSocketAddress(ip, port), type, null);
        } catch (Exception e) {
            LOG.warn("Failed to parse ICE candidate: " + e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // Utility — random credential generation
    // ========================================================================

    private static final char[] CREDENTIAL_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    static String generateCredential(SecureRandom random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = CREDENTIAL_CHARS[random.nextInt(CREDENTIAL_CHARS.length)];
        }
        return new String(chars);
    }
}
