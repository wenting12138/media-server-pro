package com.wenting.mediaserver.protocol.rtsp;

import java.util.HashSet;
import java.util.Set;

/**
 * Allocates even/odd RTP/RTCP UDP port pairs from a configured range.
 */
public final class RtpPortAllocator {

    private final int minPort;
    private final int maxPort;
    private final Set<Integer> allocatedRtpPorts = new HashSet<Integer>();

    public RtpPortAllocator(int minPort, int maxPort) {
        if (minPort <= 0 || maxPort <= 0) {
            throw new IllegalArgumentException("Port range must be positive");
        }
        if (minPort > maxPort) {
            throw new IllegalArgumentException("minPort must be <= maxPort");
        }
        int normalizedMin = (minPort % 2 == 0) ? minPort : minPort + 1;
        if (normalizedMin + 1 > maxPort) {
            throw new IllegalArgumentException("Port range must contain at least one RTP/RTCP pair");
        }
        this.minPort = normalizedMin;
        this.maxPort = maxPort;
    }

    public synchronized RtpPortAllocation allocate() {
        for (int rtpPort = minPort; rtpPort + 1 <= maxPort; rtpPort += 2) {
            if (!allocatedRtpPorts.contains(rtpPort)) {
                allocatedRtpPorts.add(rtpPort);
                return new RtpPortAllocation(rtpPort, rtpPort + 1);
            }
        }
        throw new IllegalStateException("No free RTP/RTCP port pairs available in range " + minPort + "-" + maxPort);
    }

    public synchronized void release(RtpPortAllocation allocation) {
        if (allocation == null) {
            return;
        }
        allocatedRtpPorts.remove(allocation.rtpPort());
    }

    public synchronized boolean isAllocated(int rtpPort) {
        return allocatedRtpPorts.contains(rtpPort);
    }
}
