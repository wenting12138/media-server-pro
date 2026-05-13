package com.wenting.mediaserver.protocol.webrtc.core.ice;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * ICE candidate (RFC 5245 Section 4.1).
 *
 * 对标 JS 的 RTCIceCandidate，包含候选者地址、类型、优先级等信息。
 *
 * 优先级公式 (RFC 5245 4.1.2.1):
 *   priority = (2^24) * type_preference + (2^8) * local_preference + (2^0) * (256 - component_id)
 */
public class IceCandidate implements Comparable<IceCandidate> {

    private final String foundation;
    private final int componentId;       // 1=RTP, 2=RTCP
    private final String transport;      // "UDP"
    private final int priority;
    private final InetSocketAddress address;
    private final CandidateType type;
    private final InetSocketAddress relatedAddress; // srflx/relay 的 base
    private final String base;           // base address as string

    public IceCandidate(String foundation, int componentId, String transport,
                        InetSocketAddress address, CandidateType type,
                        InetSocketAddress relatedAddress) {
        this.foundation = Objects.requireNonNull(foundation);
        this.componentId = componentId;
        this.transport = Objects.requireNonNull(transport);
        this.address = Objects.requireNonNull(address);
        this.type = Objects.requireNonNull(type);
        this.relatedAddress = relatedAddress;
        this.base = relatedAddress != null
            ? ipToString(relatedAddress) : ipToString(address);
        this.priority = calculatePriority(type, address.getAddress().getAddress().length, componentId);
    }

    /** 构造函数快捷版本 */
    public IceCandidate(String foundation, int componentId, InetSocketAddress address,
                        CandidateType type) {
        this(foundation, componentId, "UDP", address, type, null);
    }

    // ---- 访问器 ----

    public String getFoundation() { return foundation; }
    public int getComponentId() { return componentId; }
    public String getTransport() { return transport; }
    public int getPriority() { return priority; }
    public InetSocketAddress getAddress() { return address; }
    public CandidateType getType() { return type; }
    public InetSocketAddress getRelatedAddress() { return relatedAddress; }
    public String getBase() { return base; }

    /** 生成 SDP candidate attribute 行 */
    public String toSdpAttribute() {
        // a=candidate: foundation componentId transport priority ip port type [rel-addr rel-port]
        StringBuilder sb = new StringBuilder();
        sb.append("a=candidate:").append(foundation)
          .append(' ').append(componentId)
          .append(' ').append(transport)
          .append(' ').append(priority)
          .append(' ').append(address.getAddress().getHostAddress())
          .append(' ').append(address.getPort())
          .append(" typ ").append(typeToString(type));

        if (relatedAddress != null) {
            sb.append(" raddr ").append(relatedAddress.getAddress().getHostAddress())
              .append(" rport ").append(relatedAddress.getPort());
        }
        return sb.toString();
    }

    // ---- Comparable ----

    @Override
    public int compareTo(IceCandidate other) {
        // 优先级高的排在前面（降序）
        return Integer.compare(other.priority, this.priority);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IceCandidate)) return false;
        IceCandidate that = (IceCandidate) o;
        return componentId == that.componentId
            && priority == that.priority
            && foundation.equals(that.foundation)
            && address.equals(that.address)
            && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(foundation, componentId, address, type);
    }

    @Override
    public String toString() {
        return type + " " + address + " prio=" + priority + " foundation=" + foundation;
    }

    // ---- 内部方法 ----

    static int calculatePriority(CandidateType type, int addressLen, int componentId) {
        int typePref = type.preference;
        int localPref = (addressLen == 16) ? 65535 : 65535; // IPv6 preferred
        // Simplified: same local preference for both
        return (1 << 24) * typePref + (1 << 8) * localPref + (256 - componentId);
    }

    public static String typeToString(CandidateType type) {
        switch (type) {
            case HOST: return "host";
            case PEER_REFLEXIVE: return "prflx";
            case SERVER_REFLEXIVE: return "srflx";
            case RELAYED: return "relay";
            default: return "host";
        }
    }

    public static CandidateType stringToType(String s) {
        switch (s.toLowerCase()) {
            case "srflx": return CandidateType.SERVER_REFLEXIVE;
            case "prflx": return CandidateType.PEER_REFLEXIVE;
            case "relay": return CandidateType.RELAYED;
            default: return CandidateType.HOST;
        }
    }

    private static String ipToString(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }
}
