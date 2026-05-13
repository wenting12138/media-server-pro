package com.wenting.mediaserver.protocol.webrtc.core.sdp;

import io.github.webrtc.core.sdp.SdpDescription.Attribute;
import io.github.webrtc.core.sdp.SdpDescription.MediaDescription;
import io.github.webrtc.core.sdp.SdpDescription.Origin;

import java.util.ArrayList;
import java.util.List;

/**
 * SDP text parser (RFC 4566).
 *
 * 将 SDP 文本解析为 SdpDescription 对象模型。
 * 支持 WebRTC 相关的属性: ice-ufrag, ice-pwd, fingerprint, candidate, msid, ssrc, group, mid
 *
 * 用法:
 *   SdpDescription sdp = SdpParser.parse(sdpText);
 *   String ufrag = sdp.getIceUfrag();
 */
public class SdpParser {

    /**
     * 解析 SDP 文本。
     *
     * @param sdpText SDP 文本（按行分割，每行以 key=value 格式）
     * @return 解析后的 SdpDescription
     * @throws IllegalArgumentException 如果格式无效
     */
    public static SdpDescription parse(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty SDP text");
        }

        String[] lines = sdpText.split("\\r?\\n");

        int version = 0;
        Origin origin = null;
        String sessionName = "-";
        String timing = "0 0";
        List<Attribute> sessionAttrs = new ArrayList<>();
        List<MediaDescription> mediaList = new ArrayList<>();

        MediaDescription currentMedia = null;
        String currentMediaType = null;
        int currentPort = 0;
        String currentProtocol = null;
        List<Integer> currentPayloads = null;
        String currentConnection = null;
        List<Attribute> currentAttrs = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.length() < 2 || line.charAt(1) != '=') {
                continue; // skip malformed lines
            }

            char type = line.charAt(0);
            String value = line.substring(2);

            switch (type) {
                case 'v': // version
                    version = parseInt(value, "version");
                    break;

                case 'o': // origin
                    origin = parseOrigin(value);
                    break;

                case 's': // session name
                    sessionName = value;
                    break;

                case 't': // timing
                    timing = value;
                    break;

                case 'a': // attribute
                    Attribute attr = parseAttribute(value);
                    if (currentMediaType != null) {
                        currentAttrs.add(attr);
                    } else {
                        sessionAttrs.add(attr);
                    }
                    break;

                case 'm': // media description
                    // 保存上一个 media（如果有）
                    if (currentMediaType != null) {
                        mediaList.add(buildMedia(currentMediaType, currentPort,
                            currentProtocol, currentPayloads, currentConnection,
                            currentAttrs));
                    }

                    // 解析新的 media 行
                    String[] parts = value.split("\\s+");
                    if (parts.length < 4) {
                        throw new IllegalArgumentException("Invalid media line: " + value);
                    }
                    currentMediaType = parts[0];
                    currentPort = parseInt(parts[1], "media port");
                    currentProtocol = parts[2];
                    currentPayloads = new ArrayList<>();
                    for (int i = 3; i < parts.length; i++) {
                        try {
                            currentPayloads.add(Integer.parseInt(parts[i]));
                        } catch (NumberFormatException e) {
                            // Non-numeric payload (e.g., "webrtc-datachannel")
                        }
                    }
                    currentConnection = null;
                    currentAttrs = new ArrayList<>();
                    break;

                case 'c': // connection info
                    if (currentMediaType != null) {
                        currentConnection = value;
                    }
                    break;
            }
        }

        // 保存最后一个 media
        if (currentMediaType != null) {
            mediaList.add(buildMedia(currentMediaType, currentPort,
                currentProtocol, currentPayloads, currentConnection, currentAttrs));
        }

        if (origin == null) {
            throw new IllegalArgumentException("Missing o= line in SDP");
        }

        return new SdpDescription(version, origin, sessionName, timing,
            sessionAttrs, mediaList);
    }

    // ---- 内部方法 ----

    private static Origin parseOrigin(String value) {
        String[] parts = value.split("\\s+");
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid origin line: " + value);
        }
        return new Origin(
            parts[0],                          // username
            parseLong(parts[1], "session ID"),
            parseLong(parts[2], "session version"),
            parts[3],                          // nettype
            parts[4],                          // addrtype
            parts[5]                           // unicast address
        );
    }

    private static Attribute parseAttribute(String value) {
        int colon = value.indexOf(':');
        if (colon == -1) {
            return Attribute.flag(value);
        }
        return new Attribute(value.substring(0, colon), value.substring(colon + 1));
    }

    private static MediaDescription buildMedia(String type, int port, String protocol,
                                                List<Integer> payloads, String connection,
                                                List<Attribute> attrs) {
        return new MediaDescription(type, port, protocol,
            payloads, connection, attrs);
    }

    private static int parseInt(String s, String field) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + field + ": " + s, e);
        }
    }

    private static long parseLong(String s, String field) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + field + ": " + s, e);
        }
    }
}
