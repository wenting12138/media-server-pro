package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.protocol.webrtc.dtls.DtlsServerTransport;
import com.wenting.mediaserver.protocol.webrtc.ice.IceAgent;

import java.net.InetSocketAddress;

public final class WebRtcPeerSession {

    private final String sessionId;
    private final StreamKey streamKey;
    private final String offerSdp;
    private final String answerSdp;
    private final String iceUfrag;
    private final String icePwd;
    private final String fingerprint;
    private final IceAgent iceAgent;
    private final long createdAtMillis;
    private volatile InetSocketAddress remoteAddress;
    private volatile DtlsServerTransport dtlsServerTransport;

    public WebRtcPeerSession(
            String sessionId,
            StreamKey streamKey,
            String offerSdp,
            String answerSdp,
            String iceUfrag,
            String icePwd,
            String fingerprint,
            IceAgent iceAgent,
            long createdAtMillis
    ) {
        this.sessionId = sessionId;
        this.streamKey = streamKey;
        this.offerSdp = offerSdp;
        this.answerSdp = answerSdp;
        this.iceUfrag = iceUfrag;
        this.icePwd = icePwd;
        this.fingerprint = fingerprint;
        this.iceAgent = iceAgent;
        this.createdAtMillis = createdAtMillis;
    }

    public String sessionId() {
        return sessionId;
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String offerSdp() {
        return offerSdp;
    }

    public String answerSdp() {
        return answerSdp;
    }

    public String iceUfrag() {
        return iceUfrag;
    }

    public String icePwd() {
        return icePwd;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public IceAgent iceAgent() {
        return iceAgent;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public void remoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public DtlsServerTransport dtlsServerTransport() {
        return dtlsServerTransport;
    }

    public void dtlsServerTransport(DtlsServerTransport dtlsServerTransport) {
        this.dtlsServerTransport = dtlsServerTransport;
    }
}
