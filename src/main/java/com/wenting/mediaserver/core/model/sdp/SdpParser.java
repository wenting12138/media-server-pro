package com.wenting.mediaserver.core.model.sdp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal SDP parser for RTSP ANNOUNCE/DESCRIBE payloads.
 */
public final class SdpParser {

    public SdpSessionDescription parse(String rawSdp) {
        if (rawSdp == null) {
            throw new IllegalArgumentException("rawSdp must not be null");
        }

        String version = null;
        String origin = null;
        String sessionName = null;
        String connection = null;
        String timing = null;
        Map<String, String> sessionAttributes = new LinkedHashMap<String, String>();
        List<SdpMediaDescription> mediaDescriptions = new ArrayList<SdpMediaDescription>();

        String mediaType = null;
        int mediaPort = 0;
        String mediaTransportProtocol = null;
        List<String> mediaFormats = null;
        Integer mediaBandwidthAsKbps = null;
        String mediaConnection = null;
        Integer mediaPayloadType = null;
        String mediaCodecName = null;
        Integer mediaClockRate = null;
        Integer mediaChannels = null;
        String mediaControl = null;
        Map<String, String> mediaFmtpParameters = null;
        Map<String, String> mediaAttributes = null;

        String[] lines = rawSdp.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.length() < 2 || line.charAt(1) != '=') {
                continue;
            }

            String field = line.substring(0, 1);
            String value = line.substring(2).trim();
            if ("m".equals(field)) {
                if (mediaType != null) {
                    mediaDescriptions.add(buildMedia(
                            mediaType,
                            mediaPort,
                            mediaTransportProtocol,
                            mediaFormats,
                            mediaBandwidthAsKbps,
                            mediaConnection,
                            mediaPayloadType,
                            mediaCodecName,
                            mediaClockRate,
                            mediaChannels,
                            mediaControl,
                            mediaFmtpParameters,
                            mediaAttributes
                    ));
                }
                String[] mediaParts = parseMediaLine(value);
                mediaType = mediaParts[0];
                mediaPort = parseInt(mediaParts[1], 0);
                mediaTransportProtocol = mediaParts[2];
                mediaFormats = parseMediaFormats(mediaParts);
                mediaBandwidthAsKbps = null;
                mediaConnection = null;
                mediaPayloadType = null;
                mediaCodecName = null;
                mediaClockRate = null;
                mediaChannels = null;
                mediaControl = null;
                mediaFmtpParameters = new LinkedHashMap<String, String>();
                mediaAttributes = new LinkedHashMap<String, String>();
                continue;
            }

            if (mediaType == null) {
                if ("v".equals(field)) {
                    version = value;
                } else if ("o".equals(field)) {
                    origin = value;
                } else if ("s".equals(field)) {
                    sessionName = value;
                } else if ("c".equals(field)) {
                    connection = value;
                } else if ("t".equals(field)) {
                    timing = value;
                } else if ("a".equals(field)) {
                    putAttribute(sessionAttributes, value);
                }
            } else {
                if ("b".equals(field)) {
                    mediaBandwidthAsKbps = parseBandwidthAs(value);
                } else if ("c".equals(field)) {
                    mediaConnection = value;
                } else if ("a".equals(field)) {
                    putAttribute(mediaAttributes, value);
                    if (value.startsWith("control:")) {
                        mediaControl = value.substring("control:".length()).trim();
                    } else if (value.startsWith("rtpmap:")) {
                        ParsedRtpMap rtpMap = parseRtpMap(value.substring("rtpmap:".length()).trim());
                        mediaPayloadType = rtpMap.payloadType();
                        mediaCodecName = rtpMap.codecName();
                        mediaClockRate = rtpMap.clockRate();
                        mediaChannels = rtpMap.channels();
                    } else if (value.startsWith("fmtp:")) {
                        ParsedFmtp fmtp = parseFmtp(value.substring("fmtp:".length()).trim());
                        if (mediaPayloadType == null) {
                            mediaPayloadType = fmtp.payloadType();
                        }
                        mediaFmtpParameters.putAll(fmtp.parameters());
                    }
                }
            }
        }

        if (mediaType != null) {
            mediaDescriptions.add(buildMedia(
                    mediaType,
                    mediaPort,
                    mediaTransportProtocol,
                    mediaFormats,
                    mediaBandwidthAsKbps,
                    mediaConnection,
                    mediaPayloadType,
                    mediaCodecName,
                    mediaClockRate,
                    mediaChannels,
                    mediaControl,
                    mediaFmtpParameters,
                    mediaAttributes
            ));
        }

        return new SdpSessionDescription(
                version,
                origin,
                sessionName,
                connection,
                timing,
                sessionAttributes,
                mediaDescriptions
        );
    }

    private static String[] parseMediaLine(String value) {
        String[] parts = value.split("\\s+");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid SDP media line: " + value);
        }
        return parts;
    }

    private static List<String> parseMediaFormats(String[] parts) {
        List<String> formats = new ArrayList<String>();
        for (int i = 3; i < parts.length; i++) {
            formats.add(parts[i]);
        }
        return formats;
    }

    private static ParsedRtpMap parseRtpMap(String value) {
        int space = value.indexOf(' ');
        if (space < 0) {
            return new ParsedRtpMap(null, null, null, null);
        }
        Integer payloadType = parseIntObject(value.substring(0, space).trim());
        String[] parts = value.substring(space + 1).trim().split("/");
        String codecName = null;
        Integer clockRate = null;
        Integer channels = null;
        if (parts.length >= 1) {
            codecName = parts[0];
        }
        if (parts.length >= 2) {
            clockRate = parseIntObject(parts[1]);
        }
        if (parts.length >= 3) {
            channels = parseIntObject(parts[2]);
        }
        return new ParsedRtpMap(payloadType, codecName, clockRate, channels);
    }

    private static ParsedFmtp parseFmtp(String value) {
        int space = value.indexOf(' ');
        if (space < 0) {
            return new ParsedFmtp(null, new LinkedHashMap<String, String>());
        }
        Integer payloadType = parseIntObject(value.substring(0, space).trim());
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        String[] parts = value.substring(space + 1).split(";");
        for (String part : parts) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq < 0) {
                parameters.put(item, "");
            } else {
                parameters.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            }
        }
        return new ParsedFmtp(payloadType, parameters);
    }

    private static Integer parseBandwidthAs(String value) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith("AS:")) {
            return null;
        }
        return parseIntObject(value.substring(3).trim());
    }

    private static void putAttribute(Map<String, String> attributes, String value) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            attributes.put(value, "");
            return;
        }
        attributes.put(value.substring(0, colon).trim(), value.substring(colon + 1).trim());
    }

    private static int parseInt(String raw, int fallback) {
        Integer parsed = parseIntObject(raw);
        return parsed == null ? fallback : parsed.intValue();
    }

    private static Integer parseIntObject(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SdpMediaDescription buildMedia(
            String mediaType,
            int port,
            String transportProtocol,
            List<String> formats,
            Integer bandwidthAsKbps,
            String connection,
            Integer payloadType,
            String codecName,
            Integer clockRate,
            Integer channels,
            String control,
            Map<String, String> fmtpParameters,
            Map<String, String> attributes
    ) {
        return new SdpMediaDescription(
                mediaType,
                port,
                transportProtocol,
                formats,
                bandwidthAsKbps,
                connection,
                payloadType,
                codecName,
                clockRate,
                channels,
                control,
                fmtpParameters,
                attributes
        );
    }
}
