package com.wenting.mediaserver.protocol.webrtc.core.ice;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * ICE candidate gatherer.
 *
 * 从本地网络接口收集 HOST 类型的候选者（IP:port），
 * 为每个接口生成一个 IceCandidate。
 */
public class CandidateGatherer {

    private static final int COMPONENT_RTP = 1;

    private final int port;

    public CandidateGatherer(int port) {
        this.port = port;
    }

    /**
     * Gather local HOST candidates from all active network interfaces,
     * including both IPv4 and IPv6 addresses.
     */
    public List<IceCandidate> gatherHostCandidates() throws SocketException {
        List<IceCandidate> candidates = new ArrayList<>();

        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        if (ifaces == null) return Collections.emptyList();

        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (!iface.isUp() || iface.isLoopback()) continue;

            Enumeration<InetAddress> addrs = iface.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (addr.isLoopbackAddress()) continue;
                if (addr.isLinkLocalAddress()) continue;

                // Foundation: same NIC + address type = same foundation (RFC 5245 §4.1.1.1)
                String addrType = (addr instanceof Inet6Address) ? "v6" : "v4";
                String foundation = iface.getName() + "-host-" + addrType;

                candidates.add(new IceCandidate(
                    foundation,
                    COMPONENT_RTP,
                    new java.net.InetSocketAddress(addr, port),
                    CandidateType.HOST
                ));
            }
        }

        Collections.sort(candidates);
        return candidates;
    }

    /**
     * Gather only IPv4 HOST candidates (original behavior for backwards compat).
     */
    public List<IceCandidate> gather() throws SocketException {
        List<IceCandidate> candidates = new ArrayList<>();
        int foundationCounter = 0;

        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        if (ifaces == null) return Collections.emptyList();

        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (!iface.isUp() || iface.isLoopback()) continue;

            Enumeration<InetAddress> addrs = iface.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (addr.isLoopbackAddress()) continue;
                if (addr.isLinkLocalAddress()) continue;
                if (!(addr instanceof Inet4Address)) continue;

                String foundation = "host-" + (++foundationCounter);
                candidates.add(new IceCandidate(
                    foundation,
                    COMPONENT_RTP,
                    new java.net.InetSocketAddress(addr, port),
                    CandidateType.HOST
                ));
            }
        }

        Collections.sort(candidates);
        return candidates;
    }

    /**
     * 为两个 agent 创建测试用的候选者列表（用于 loopback 测试）。
     * 返回两个列表，分别模拟 caller 和 callee 的本地候选者。
     */
    public static List<List<IceCandidate>> createLoopbackCandidates(int portA, int portB) {
        List<IceCandidate> listA = new ArrayList<>();
        listA.add(new IceCandidate("1", 1,
            new java.net.InetSocketAddress("127.0.0.1", portA), CandidateType.HOST));

        List<IceCandidate> listB = new ArrayList<>();
        listB.add(new IceCandidate("1", 1,
            new java.net.InetSocketAddress("127.0.0.1", portB), CandidateType.HOST));

        List<List<IceCandidate>> result = new ArrayList<>();
        result.add(listA);
        result.add(listB);
        return result;
    }
}
