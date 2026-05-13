package com.wenting.mediaserver.protocol.webrtc.core.sdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SDP session description (RFC 4566).
 *
 * 顶层模型，包含 session-level 信息和 media-level 描述列表。
 * 对应 WebRTC 中的 RTCSessionDescription 的 sdp 文本的结构化表示。
 */
public class SdpDescription {

    private final int version;
    private final Origin origin;
    private final String sessionName;
    private final String timing;
    private final List<Attribute> sessionAttributes;
    private final List<MediaDescription> mediaDescriptions;

    public SdpDescription(int version, Origin origin, String sessionName, String timing,
                           List<Attribute> sessionAttributes,
                           List<MediaDescription> mediaDescriptions) {
        this.version = version;
        this.origin = Objects.requireNonNull(origin);
        this.sessionName = sessionName != null ? sessionName : "-";
        this.timing = timing != null ? timing : "0 0";
        this.sessionAttributes = sessionAttributes != null
            ? Collections.unmodifiableList(new ArrayList<>(sessionAttributes))
            : Collections.emptyList();
        this.mediaDescriptions = mediaDescriptions != null
            ? Collections.unmodifiableList(new ArrayList<>(mediaDescriptions))
            : Collections.emptyList();
    }

    // ---- 便捷访问 ----

    public int getVersion() { return version; }
    public Origin getOrigin() { return origin; }
    public String getSessionName() { return sessionName; }
    public String getTiming() { return timing; }
    public List<Attribute> getSessionAttributes() { return sessionAttributes; }
    public List<MediaDescription> getMediaDescriptions() { return mediaDescriptions; }

    /** 获取 session-level 的 ICE ufrag */
    public String getIceUfrag() {
        return getAttributeValue("ice-ufrag");
    }

    /** 获取 session-level 的 ICE pwd */
    public String getIcePwd() {
        return getAttributeValue("ice-pwd");
    }

    /** 获取 fingerprint (sha-256 的哈希值) */
    public String getFingerprint() {
        String fp = getAttributeValue("fingerprint");
        if (fp != null && fp.startsWith("sha-256 ")) {
            return fp.substring(8);
        }
        return fp;
    }

    private String getAttributeValue(String key) {
        for (Attribute attr : sessionAttributes) {
            if (attr.key.equals(key)) {
                return attr.value;
            }
        }
        for (MediaDescription md : mediaDescriptions) {
            for (Attribute attr : md.attributes) {
                if (attr.key.equals(key)) {
                    return attr.value;
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "SdpDescription{media=" + mediaDescriptions.size()
            + ", iceUfrag=" + getIceUfrag() + "}";
    }

    // ---- Origin ----

    /** o= 行: username, sessionId, sessionVersion, nettype, addrtype, unicastAddress */
    public static class Origin {
        public final String username;
        public final long sessionId;
        public final long sessionVersion;
        public final String netType;
        public final String addressType;
        public final String unicastAddress;

        public Origin(String username, long sessionId, long sessionVersion,
                      String netType, String addressType, String unicastAddress) {
            this.username = username;
            this.sessionId = sessionId;
            this.sessionVersion = sessionVersion;
            this.netType = netType;
            this.addressType = addressType;
            this.unicastAddress = unicastAddress;
        }
    }

    // ---- Attribute ----

    /** a= 行。key=value 形式，或者只有 key（无值 flag）。 */
    public static class Attribute {
        public final String key;
        public final String value; // null for flag-type attributes

        public Attribute(String key, String value) {
            this.key = Objects.requireNonNull(key);
            this.value = value;
        }

        /** 创建 flag 属性 (只有 key，没有值) */
        public static Attribute flag(String key) {
            return new Attribute(key, null);
        }

        @Override
        public String toString() {
            return value != null ? key + ":" + value : key;
        }
    }

    // ---- Media Description ----

    /** m= 行及其属性。包含媒体类型、端口、协议、payload 类型列表。 */
    public static class MediaDescription {
        public final String mediaType;      // audio, video, application
        public final int port;
        public final String protocol;       // UDP/TLS/RTP/SAVPF, DTLS/SCTP
        public final List<Integer> payloadTypes;
        public final String connectionInfo; // c= 行
        public final List<Attribute> attributes;

        public MediaDescription(String mediaType, int port, String protocol,
                                 List<Integer> payloadTypes, String connectionInfo,
                                 List<Attribute> attributes) {
            this.mediaType = mediaType;
            this.port = port;
            this.protocol = protocol;
            this.payloadTypes = payloadTypes != null
                ? Collections.unmodifiableList(new ArrayList<>(payloadTypes))
                : Collections.emptyList();
            this.connectionInfo = connectionInfo;
            this.attributes = attributes != null
                ? Collections.unmodifiableList(new ArrayList<>(attributes))
                : Collections.emptyList();
        }

        /** 获取此 media 的 mid */
        public String getMid() {
            for (Attribute attr : attributes) {
                if ("mid".equals(attr.key)) {
                    return attr.value;
                }
            }
            return null;
        }

        /** Extract the first SSRC from a=ssrc attributes, or null if none */
        public Long getSsrc() {
            for (Attribute attr : attributes) {
                if ("ssrc".equals(attr.key) && attr.value != null) {
                    String[] parts = attr.value.split("\\s+");
                    if (parts.length > 0) {
                        try { return Long.parseLong(parts[0]); }
                        catch (NumberFormatException e) { /* ignore */ }
                    }
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return mediaType + " " + port + " " + protocol
                + " pt=" + payloadTypes + " mid=" + getMid();
        }
    }
}
