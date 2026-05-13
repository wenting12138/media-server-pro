package com.wenting.mediaserver.protocol.webrtc.core.sdp;

import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.Attribute;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.MediaDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription.Origin;

import java.util.ArrayList;
import java.util.List;

/**
 * SDP text builder.
 *
 * 从 SdpDescription 模型生成 SDP 文本。
 * 提供便捷的工厂方法创建 WebRTC 常用的 SDP offer/answer。
 *
 * 用法:
 *   String sdp = new SdpBuilder()
 *       .addSessionAttributes(iceUfrag, icePwd, fingerprint)
 *       .addMedia("audio", 9, "UDP/TLS/RTP/SAVPF", 111)
 *       .addAttribute("rtpmap", "111 opus/48000/2")
 *       .addMedia("video", 9, "UDP/TLS/RTP/SAVPF", 96)
 *       .addAttribute("rtpmap", "96 H264/90000")
 *       .build();
 */
public class SdpBuilder {

    private int version = 0;
    private Origin origin;
    private String sessionName = "-";
    private String timing = "0 0";
    private final List<Attribute> sessionAttrs = new ArrayList<>();
    private final List<MediaBuilder> mediaBuilders = new ArrayList<>();

    public SdpBuilder() {
    }

    /** 设置 origin (o= 行) */
    public SdpBuilder setOrigin(Origin origin) {
        this.origin = origin;
        return this;
    }

    /** 设置 origin (o= 行) 便捷方法 */
    public SdpBuilder setOrigin(String username, long sessionId, String address) {
        this.origin = new Origin(username, sessionId, 2, "IN", "IP4", address);
        return this;
    }

    /** 添加 session-level 属性 */
    public SdpBuilder addSessionAttribute(String key, String value) {
        sessionAttrs.add(new Attribute(key, value));
        return this;
    }

    /** 设置 WebRTC 相关的 session 属性 (ICE + fingerprint) */
    public SdpBuilder addWebrtcSessionAttributes(String iceUfrag, String icePwd,
                                                  String fingerprint) {
        sessionAttrs.add(new Attribute("ice-ufrag", iceUfrag));
        sessionAttrs.add(new Attribute("ice-pwd", icePwd));
        sessionAttrs.add(new Attribute("fingerprint", "sha-256 " + fingerprint));
        sessionAttrs.add(new Attribute("group", "BUNDLE 0 1"));
        return this;
    }

    /** 添加一个新的 media section */
    public MediaBuilder addMedia(String mediaType, int port, String protocol,
                                  int... payloadTypes) {
        MediaBuilder mb = new MediaBuilder(this, mediaType, port, protocol, payloadTypes);
        mediaBuilders.add(mb);
        return mb;
    }

    /** 生成 SDP 文本 */
    public String build() {
        StringBuilder sb = new StringBuilder();

        sb.append("v=").append(version).append("\r\n");

        if (origin != null) {
            sb.append("o=").append(origin.username).append(" ")
              .append(origin.sessionId).append(" ")
              .append(origin.sessionVersion).append(" ")
              .append(origin.netType).append(" ")
              .append(origin.addressType).append(" ")
              .append(origin.unicastAddress).append("\r\n");
        } else {
            sb.append("o=- 0 2 IN IP4 127.0.0.1\r\n");
        }

        sb.append("s=").append(sessionName).append("\r\n");
        sb.append("t=").append(timing).append("\r\n");

        for (Attribute attr : sessionAttrs) {
            sb.append("a=");
            if (attr.value != null) {
                sb.append(attr.key).append(":").append(attr.value);
            } else {
                sb.append(attr.key);
            }
            sb.append("\r\n");
        }

        for (MediaBuilder mb : mediaBuilders) {
            sb.append("m=").append(mb.mediaType).append(" ")
              .append(mb.port).append(" ")
              .append(mb.protocol);

            for (int pt : mb.payloadTypes) {
                sb.append(" ").append(pt);
            }
            sb.append("\r\n");

            if (mb.connectionInfo != null) {
                sb.append("c=").append(mb.connectionInfo).append("\r\n");
            }

            for (Attribute attr : mb.attributes) {
                sb.append("a=");
                if (attr.value != null) {
                    sb.append(attr.key).append(":").append(attr.value);
                } else {
                    sb.append(attr.key);
                }
                sb.append("\r\n");
            }
        }

        return sb.toString();
    }

    // ---- MediaBuilder ----

    public static class MediaBuilder {
        private final SdpBuilder parent;
        private final String mediaType;
        private final int port;
        private final String protocol;
        private final int[] payloadTypes;
        private String connectionInfo;
        private final List<Attribute> attributes = new ArrayList<>();

        MediaBuilder(SdpBuilder parent, String mediaType, int port,
                     String protocol, int[] payloadTypes) {
            this.parent = parent;
            this.mediaType = mediaType;
            this.port = port;
            this.protocol = protocol;
            this.payloadTypes = payloadTypes;
        }

        public MediaBuilder setConnectionInfo(String info) {
            this.connectionInfo = info;
            return this;
        }

        public MediaBuilder addAttribute(String key, String value) {
            attributes.add(new Attribute(key, value));
            return this;
        }

        /** Add a flag attribute (no value, e.g. a=sendrecv) */
        public MediaBuilder addAttribute(String key) {
            attributes.add(new Attribute(key, null));
            return this;
        }

        public MediaBuilder addAttribute(Attribute attr) {
            attributes.add(attr);
            return this;
        }

        public MediaBuilder addCandidate(IceCandidateInfo ci) {
            String candStr = ci.foundation + " " + ci.componentId + " " + ci.transport
                + " " + ci.priority + " " + ci.address + " " + ci.port
                + " typ " + ci.type;
            if (ci.relatedAddr != null) {
                candStr += " raddr " + ci.relatedAddr + " rport " + ci.relatedPort;
            }
            attributes.add(new Attribute("candidate", candStr));
            return this;
        }

        /** 结束 media 构造，返回 parent builder */
        public SdpBuilder done() {
            return parent;
        }
    }

    // ---- IceCandidateInfo ----

    /** 便捷类，用于在 SDP 中生成 a=candidate 行 */
    public static class IceCandidateInfo {
        public final String foundation;
        public final int componentId;
        public final String transport;
        public final long priority;
        public final String address;
        public final int port;
        public final String type;

        public final String relatedAddr;  // null for host candidates
        public final int relatedPort;     // 0 for host candidates

        public IceCandidateInfo(String foundation, int componentId, String transport,
                                 long priority, String address, int port, String type) {
            this(foundation, componentId, transport, priority, address, port, type, null, 0);
        }

        public IceCandidateInfo(String foundation, int componentId, String transport,
                                 long priority, String address, int port, String type,
                                 String relatedAddr, int relatedPort) {
            this.foundation = foundation;
            this.componentId = componentId;
            this.transport = transport;
            this.priority = priority;
            this.address = address;
            this.port = port;
            this.type = type;
            this.relatedAddr = relatedAddr;
            this.relatedPort = relatedPort;
        }
    }
}
