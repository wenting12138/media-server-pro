package com.wenting.mediaserver.protocol.webrtc.api;

import com.wenting.mediaserver.core.codec.rtp.RtpParseResult;
import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.protocol.webrtc.WebRtcUdpBootstrap;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.DtlsHandshake;
import com.wenting.mediaserver.protocol.webrtc.core.dtls.UdpDatagramTransport;
import com.wenting.mediaserver.protocol.webrtc.core.ice.*;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpAssociation;
import com.wenting.mediaserver.protocol.webrtc.util.ReportPacketUtil;
import com.wenting.mediaserver.protocol.webrtc.util.WebrtcAnswerSdpGenerator;
import com.wenting.mediaserver.protocol.webrtc.util.WebrtcPacketUtil;
import com.wenting.mediaserver.protocol.webrtc.util.WebrtcSdpUtil;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DTLSTransport;
import com.wenting.mediaserver.protocol.webrtc.core.rtp.RtpPacket;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.DataChannel;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpTransport;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.*;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.MediaDescription;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpCryptoContext;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpException;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtcpTransform;
import com.wenting.mediaserver.protocol.webrtc.core.srtp.SrtpTransform;
import com.wenting.mediaserver.protocol.webrtc.core.stun.StunMessage;
import com.wenting.mediaserver.protocol.webrtc.transport.DatagramIo;
import com.wenting.mediaserver.protocol.webrtc.transport.UdpTransport;
import com.wenting.mediaserver.core.codec.rtcp.RtcpGenericNackPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpPictureLossIndicationPacket;
import com.wenting.mediaserver.core.codec.rtcp.RtcpReportBlock;
import com.wenting.mediaserver.core.codec.rtp.RtpPacketParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.security.Security;
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
    public volatile Consumer<RtcpPacket> onRtcpPacket;
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

    // ---- Media / SRTP ----
    private final List<RTCRtpTransceiver> transceivers = new CopyOnWriteArrayList<>();
    private final AtomicLong nextSsrc = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Consumer<byte[]>> ssrcHandlers = new ConcurrentHashMap<>();
    private volatile boolean srtpActive = false;
    private volatile InetSocketAddress remoteAddress;
    private volatile SrtpCryptoContext inboundSrtcpContext;
    private final List<RTCIceCandidate> pendingRemoteIceCandidates = new ArrayList<>();
    private final Set<String> remoteCandidateSignatures = new HashSet<>();

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
    private volatile boolean sctpNegotiated = false;
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
    private static final long INBOUND_ACTIVITY_TIMEOUT_MS = 35000;
    private volatile boolean connectionMonitorStarted = false;
    private volatile long lastInboundActivityAtMs = System.currentTimeMillis();
    private final ScheduledExecutorService connectionMonitor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pc-monitor");
            t.setDaemon(true);
            return t;
        });
    private final RtpPacketParser rtpPacketParser = new RtpPacketParser();

    // ========================================================================
    // Constructor
    // ========================================================================

    public RTCPeerConnection() throws RTCPeerConnectionException {
        this(new UdpTransport(0), true);
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
        this.localUfrag = WebrtcSdpUtil.generateCredential(random, 4);
        this.localPwd = WebrtcSdpUtil.generateCredential(random, 22);

        // Pre-generate DTLS certificate + fingerprint
        try {
            SecureRandom sr = new SecureRandom();
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }
            org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto crypto =
                new org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider()
                    .setProvider("BC")
                    .create(sr);
            this.certCredentials = DtlsHandshake.generateSelfSignedCert(crypto);
            this.localFingerprint = DtlsHandshake.computeFingerprint(certCredentials);
        } catch (Exception e) {
            close();
            throw new RTCPeerConnectionException("Failed to generate DTLS certificate", e);
        }

        // Set up STUN/DTLS demux handler
        setupPacketHandler();
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
            this.sctpNegotiated = WebrtcSdpUtil.hasSctpMedia(parsed);

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
            importRemoteCandidatesFromSdp(parsed);

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

        RTCRtpTransceiver.Direction remoteDirection = WebrtcSdpUtil.extractDirection(md);

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
            transceiver.setDirection(WebrtcSdpUtil.invertDirection(remoteDirection));

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
                transceiver.setDirection(WebrtcSdpUtil.invertDirection(remoteDirection));
                transceivers.add(transceiver);
                LOG.info("Created " + kind + " transceiver from answer mid=" + mid + " remote-ssrc=" + remoteSsrc);

                if (remoteDirection == RTCRtpTransceiver.Direction.SENDRECV
                    || remoteDirection == RTCRtpTransceiver.Direction.SENDONLY) {
                    fireOnTrack(receiver);
                }
            }
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
        if (candidate == null || candidate.getCandidate() == null
            || candidate.getCandidate().trim().isEmpty()) {
            return;
        }
        String signature = candidate.getCandidate().trim();
        if (!remoteCandidateSignatures.add(signature)) {
            return;
        }
        if (iceAgent == null) {
            pendingRemoteIceCandidates.add(candidate);
            return;
        }
        addRemoteCandidateToIceAgent(candidate);
        maybeStartIceConnectivityChecks();
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

    public boolean sendReceiverReport(long senderSsrc, RtcpReportBlock reportBlock) {
        if (reportBlock == null) {
            return false;
        }
        InetSocketAddress addr = remoteAddress;
        SrtpCryptoContext context = resolveInboundSrtcpContext();
        if (addr == null || context == null) {
            return false;
        }
        byte[] plainRtcp = ReportPacketUtil.encodeReceiverReportPacket(senderSsrc, Collections.singletonList(reportBlock));
        byte[] protectedRtcp = new SrtcpTransform(context).protect(plainRtcp);
        transport.send(protectedRtcp, addr).exceptionally(ex -> {
            LOG.warn("Failed to send SRTCP receiver report: {}", ex.getMessage());
            return null;
        });
        return true;
    }

    public boolean sendGenericNack(long senderSsrc, long mediaSsrc, List<Integer> lostSequenceNumbers) {
        if (lostSequenceNumbers == null || lostSequenceNumbers.isEmpty()) {
            return false;
        }
        InetSocketAddress addr = remoteAddress;
        SrtpCryptoContext context = resolveInboundSrtcpContext();
        if (addr == null || context == null) {
            return false;
        }
        byte[] plainRtcp = ReportPacketUtil.encodeGenericNackPacket(senderSsrc, mediaSsrc, lostSequenceNumbers);
        byte[] protectedRtcp = new SrtcpTransform(context).protect(plainRtcp);
        transport.send(protectedRtcp, addr).exceptionally(ex -> {
            LOG.warn("Failed to send SRTCP generic NACK: {}", ex.getMessage());
            return null;
        });
        return true;
    }

    public boolean sendPictureLossIndication(long senderSsrc, long mediaSsrc) {
        InetSocketAddress addr = remoteAddress;
        SrtpCryptoContext context = resolveInboundSrtcpContext();
        if (addr == null || context == null) {
            return false;
        }
        byte[] plainRtcp = ReportPacketUtil.encodePictureLossIndicationPacket(senderSsrc, mediaSsrc);
        byte[] protectedRtcp = new SrtcpTransform(context).protect(plainRtcp);
        transport.send(protectedRtcp, addr).exceptionally(ex -> {
            LOG.warn("Failed to send SRTCP PLI: {}", ex.getMessage());
            return null;
        });
        return true;
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
        this.localUfrag = WebrtcSdpUtil.generateCredential(random, 4);
        this.localPwd = WebrtcSdpUtil.generateCredential(random, 22);

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

    private synchronized IceAgent createIceAgent(IceAgent.Role role) throws RTCPeerConnectionException {
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
                    onIcePairNominated(event.getPair());
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
        flushPendingRemoteCandidatesToIceAgent();
        maybeStartIceConnectivityChecks();

        return iceAgent;
    }

    private void fireIceCandidate(IceCandidate c) {
        Consumer<RTCIceCandidate> handler = onIceCandidate;
        if (handler != null) {
            handler.accept(new RTCIceCandidate(c.toSdpAttribute(), "0", 0));
        }
    }

    // ========================================================================
    // Internal — ICE event handlers
    // ========================================================================

    private synchronized void onIcePairSucceeded(CandidatePair pair) {
        if (iceConnectionState == IceConnectionState.CLOSED) return;
        if (iceConnectionState == IceConnectionState.CONNECTED
            || iceConnectionState == IceConnectionState.COMPLETED) {
            return;
        }
        setIceState(IceConnectionState.CONNECTED);
    }

    private synchronized void onIcePairNominated(CandidatePair pair) {
        if (pair == null || iceConnectionState == IceConnectionState.CLOSED) {
            return;
        }
        this.remoteAddress = pair.getRemote().getAddress();
        if (iceConnectionState != IceConnectionState.CONNECTED
            && iceConnectionState != IceConnectionState.COMPLETED) {
            setIceState(IceConnectionState.CONNECTED);
        }
        LOG.info("ICE pair nominated, starting DTLS: " + pair);
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
                // Connectivity succeeded, but DTLS waits for nomination.
                break;
            case COMPLETED:
                setIceState(IceConnectionState.COMPLETED);
                LOG.debug("ICE completed, selected pair: " + iceAgent.getSelectedPair());
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

        // Determine DTLS role primarily from SDP setup, fallback to ICE role.
        boolean isDtlsServer = resolveDtlsServerRole();
        LOG.info("Starting DTLS role={} iceRole={} remote={}",
            isDtlsServer ? "server" : "client",
            iceAgent.getRole(),
            remoteAddr);

        setConnectionState(ConnectionState.CONNECTING);

        Thread dtlsThread = new Thread(() -> {
            try {
                if (iceConnectionState == IceConnectionState.CLOSED) return;

                // Create DTLS transport using the shared receive queue
                UdpDatagramTransport dtlsTransport = new UdpDatagramTransport(
                    transport, remoteAddr, dtlsReceiveQueue);

                // 带超时的 DTLS 握手
                DtlsHandshake handshake = DtlsHandshake.handshakeWithTimeout(
                    dtlsTransport, isDtlsServer, certCredentials, remoteFingerprint,
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
                if (expectsSctp()) {
                    startSctp(handshake.getDtlsTransport());
                } else {
                    // Media-only session: DTLS/SRTP is enough to mark connected.
                    setConnectionState(ConnectionState.CONNECTED);
                }

                // 启动连接健康监控
                startConnectionMonitor();
            } catch (Exception e) {
                LOG.warn("DTLS/SCTP setup failed: {}", e.getMessage(), e);
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
    void checkConnectionHealthNow() {
        if (connectionState == ConnectionState.CLOSED
            || connectionState == ConnectionState.FAILED) {
            return;
        }

        long idleMs = System.currentTimeMillis() - lastInboundActivityAtMs;
        if ((iceConnectionState == IceConnectionState.CONNECTED
                || iceConnectionState == IceConnectionState.COMPLETED
                || connectionState == ConnectionState.CONNECTED)
            && idleMs > INBOUND_ACTIVITY_TIMEOUT_MS) {
            LOG.warn("Connection monitor: no inbound packets for {}ms, marking connection failed",
                idleMs);
            if (iceConnectionState != IceConnectionState.CLOSED) {
                setIceState(IceConnectionState.FAILED);
            }
            setConnectionState(ConnectionState.FAILED);
        }
    }

    private synchronized void startConnectionMonitor() {
        if (connectionMonitorStarted) return;
        connectionMonitorStarted = true;

        connectionMonitor.scheduleAtFixedRate(() -> {
            try {
                checkConnectionHealthNow();
                if (connectionState == ConnectionState.CLOSED
                    || connectionState == ConnectionState.FAILED) {
                    return;
                }

                // DTLS 健康检查不发送空包：
                // BouncyCastle DTLSTransport 不接受 len=0 的 send，
                // 会抛 IllegalArgumentException("'off' is an invalid offset: 0")。
                // 这里改为仅检查对象存在，连接生死交给 SCTP 状态与实际收发路径判定。
                if (dtlsHandshake != null && dtlsHandshake.getDtlsTransport() != null) {
                    // no-op
                }

                // 检查 SCTP 状态
                if (expectsSctp() && sctpTransport != null && sctpTransport.getAssociation() != null) {
                    SctpAssociation.State sctpState =
                        sctpTransport.getAssociation().getState();
                    if (sctpState == SctpAssociation.State.CLOSED
                        || sctpState == SctpAssociation.State.COOKIE_WAIT
                        || sctpState == SctpAssociation.State.COOKIE_ECHOED) {
                        LOG.warn("Connection monitor: SCTP state is " + sctpState
                            + ", connection state is " + connectionState);
                    }
                }
            } catch (Exception e) {
                LOG.error("Connection monitor check failed: " + e.getMessage());
            }
        }, CONNECTION_MONITOR_INTERVAL_MS, CONNECTION_MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

        LOG.debug("Connection health monitor started (interval="
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
                LOG.debug("Registered SRTP demux for SSRC " + peerSsrc + " (" + transceiver.getKind() + ")");
            }
        }
        inboundSrtcpContext = transceivers.isEmpty() ? null : transceivers.get(0).getReceiver().getSrtpContext();
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
            if (data == null || data.length == 0) {
                return;
            }
            noteInboundActivity();
            if (WebrtcPacketUtil.isStunPacket(data)) {
                handleStunPacket(data, remote);
            } else if (WebrtcPacketUtil.isDtlsPacket(data)) {
                handleDtlsPacket(data, remote);
            } else if (WebrtcPacketUtil.isRtcpPacket(data)) {
                handleRtcpPacket(data, remote);
            } else if (WebrtcPacketUtil.isRtpPacket(data)) {
                handleRtpPacket(data, remote);
            }
        });
    }

    private void noteInboundActivity() {
        lastInboundActivityAtMs = System.currentTimeMillis();
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

    private void handleDtlsPacket(byte[] data, InetSocketAddress remote) {
        dtlsReceiveQueue.offer(data);
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

    private void handleRtcpPacket(byte[] data, InetSocketAddress remote) {
        SrtpCryptoContext context = resolveInboundSrtcpContext();
        if (context == null) {
            LOG.debug("Dropping SRTCP packet before SRTP contexts are ready from {}", remote);
            return;
        }
        try {
            byte[] plainRtcp = new SrtcpTransform(context).unprotect(data);
            RtpParseResult parseResult = rtpPacketParser.parse(plainRtcp);
            if (parseResult == null || !parseResult.rtcp() || parseResult.rtcpPacket() == null) {
                return;
            }
            dispatchInboundRtcpPacket(parseResult.rtcpPacket());
        } catch (SrtpException e) {
            LOG.debug("SRTCP unprotect failed from {}: {}", remote, e.getMessage());
        }
    }

    private void dispatchInboundRtcpPacket(RtcpPacket packet) {
        Consumer<RtcpPacket> rtcpPacketHandler = onRtcpPacket;
        if (rtcpPacketHandler != null) {
            try {
                rtcpPacketHandler.accept(packet);
            } catch (RuntimeException e) {
                LOG.warn("RTCP packet callback failed: {}", e.getMessage(), e);
            }
        }
        dispatchRtcpFeedback(packet);
    }

    private SrtpCryptoContext resolveInboundSrtcpContext() {
        SrtpCryptoContext context = inboundSrtcpContext;
        if (context != null) {
            return context;
        }
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver == null || transceiver.getReceiver() == null) {
                continue;
            }
            context = transceiver.getReceiver().getSrtpContext();
            if (context != null) {
                inboundSrtcpContext = context;
                return context;
            }
        }
        return null;
    }

    private synchronized void importRemoteCandidatesFromSdp(SdpDescription parsed)
            throws RTCPeerConnectionException {
        if (parsed == null) {
            return;
        }
        for (SdpDescription.Attribute attr : parsed.getSessionAttributes()) {
            addSdpAttributeCandidate(attr);
        }
        for (MediaDescription mediaDescription : parsed.getMediaDescriptions()) {
            if (mediaDescription == null || mediaDescription.attributes == null) {
                continue;
            }
            for (SdpDescription.Attribute attr : mediaDescription.attributes) {
                addSdpAttributeCandidate(attr);
            }
        }
        maybeStartIceConnectivityChecks();
    }

    private void addSdpAttributeCandidate(SdpDescription.Attribute attr)
            throws RTCPeerConnectionException {
        if (attr == null || !"candidate".equals(attr.key) || attr.value == null) {
            return;
        }
        addIceCandidate(new RTCIceCandidate(attr.value, null, 0));
    }

    private void addRemoteCandidateToIceAgent(RTCIceCandidate candidate)
            throws RTCPeerConnectionException {
        if (iceAgent == null) {
            throw new RTCPeerConnectionException("ICE agent not initialized (call createOffer/createAnswer first)");
        }
        IceCandidate parsed = WebrtcSdpUtil.parseCandidate(candidate.getCandidate(), remoteUfrag);
        if (parsed != null) {
            iceAgent.addRemoteCandidate(parsed);
        }
    }

    private void flushPendingRemoteCandidatesToIceAgent() throws RTCPeerConnectionException {
        if (iceAgent == null || pendingRemoteIceCandidates.isEmpty()) {
            return;
        }
        List<RTCIceCandidate> queued = new ArrayList<>(pendingRemoteIceCandidates);
        pendingRemoteIceCandidates.clear();
        for (RTCIceCandidate candidate : queued) {
            addRemoteCandidateToIceAgent(candidate);
        }
    }

    private void maybeStartIceConnectivityChecks() {
        if (iceStarted || !remoteDescriptionSet || iceAgent == null) {
            return;
        }
        if (iceAgent.getRemoteCandidates().isEmpty() || !iceAgent.isGatheringComplete()) {
            return;
        }
        iceStarted = true;
        iceAgent.startConnectivityChecks();
    }

    private void dispatchRtcpFeedback(RtcpPacket packet) {
        if (packet instanceof RtcpPictureLossIndicationPacket) {
            RtcpPictureLossIndicationPacket pli = (RtcpPictureLossIndicationPacket) packet;
            RTCRtpSender sender = findSenderBySsrc(pli.mediaSsrc());
            if (sender != null && sender.getFeedbackListener() != null) {
                sender.getFeedbackListener().onPictureLossIndication(pli.mediaSsrc());
            }
            return;
        }
        if (packet instanceof RtcpGenericNackPacket) {
            RtcpGenericNackPacket nack = (RtcpGenericNackPacket) packet;
            RTCRtpSender sender = findSenderBySsrc(nack.mediaSsrc());
            if (sender != null && sender.getFeedbackListener() != null) {
                sender.getFeedbackListener().onGenericNack(nack.mediaSsrc(), nack.lostSequenceNumbers());
            }
        }
    }

    private RTCRtpSender findSenderBySsrc(long mediaSsrc) {
        long normalized = mediaSsrc & 0xFFFFFFFFL;
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver == null || transceiver.getSender() == null) {
                continue;
            }
            if ((transceiver.getSender().getSsrc() & 0xFFFFFFFFL) == normalized) {
                return transceiver.getSender();
            }
        }
        return null;
    }

    // ========================================================================
    // Internal — SDP builder
    // ========================================================================
    private String buildSdp(String type, IceAgent agent) {
        if ("answer".equals(type) && remoteSdp != null) {
            return WebrtcSdpUtil.buildAnswerSdp(agent, remoteSdp, transport, transceivers, localUfrag, localPwd, localFingerprint);
        }
        if ("offer".equals(type)) {
            return WebrtcSdpUtil.buildOfferSdp(agent, transport, transceivers, localUfrag, localPwd, localFingerprint);
        }
        return null;
    }

    private boolean expectsSctp() {
        return sctpNegotiated || !dataChannels.isEmpty() || !pendingDataChannels.isEmpty();
    }

    private boolean resolveDtlsServerRole() {
        boolean fallback = iceAgent != null && iceAgent.getRole() == IceAgent.Role.CONTROLLED;
        String localSetup = WebrtcSdpUtil.extractSetupAttribute(localDescription != null ? localDescription.getSdp() : null);
        if ("passive".equalsIgnoreCase(localSetup)) {
            return true;
        }
        if ("active".equalsIgnoreCase(localSetup)) {
            return false;
        }
        String remoteSetup = WebrtcSdpUtil.extractSetupAttribute(remoteSdp);
        if ("active".equalsIgnoreCase(remoteSetup)) {
            return true;
        }
        if ("passive".equalsIgnoreCase(remoteSetup)) {
            return false;
        }
        return fallback;
    }

}
