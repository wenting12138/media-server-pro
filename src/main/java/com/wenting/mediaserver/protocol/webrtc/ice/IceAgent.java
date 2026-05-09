package com.wenting.mediaserver.protocol.webrtc.ice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IceAgent {

    private final String localUfrag;
    private final String localPwd;
    private final List<IceCandidate> localCandidates = new ArrayList<IceCandidate>();

    public IceAgent(String localUfrag, String localPwd) {
        this.localUfrag = localUfrag;
        this.localPwd = localPwd;
    }

    public String localUfrag() {
        return localUfrag;
    }

    public String localPwd() {
        return localPwd;
    }

    public void addHostCandidate(String address, int port) {
        localCandidates.add(new IceCandidate(
                "1",
                1,
                "udp",
                2130706431L,
                address,
                port,
                IceCandidateType.HOST
        ));
    }

    public List<IceCandidate> localCandidates() {
        return Collections.unmodifiableList(new ArrayList<IceCandidate>(localCandidates));
    }

    public IceCandidate defaultCandidate() {
        return localCandidates.isEmpty() ? null : localCandidates.get(0);
    }

    public boolean acceptsRemoteUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        String normalized = username.trim();
        return normalized.endsWith(":" + localUfrag) || normalized.equals(localUfrag);
    }
}
