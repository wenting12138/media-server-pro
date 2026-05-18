package com.wenting.mediaserver.core.publish;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.sdp.SdpMediaDescription;
import com.wenting.mediaserver.core.model.sdp.SdpSessionDescription;

import java.util.Base64;
import java.util.Locale;

public final class PublishedTrackMetadataResolver {

    private PublishedTrackMetadataResolver() {
    }

    public static TrackType resolveTrackType(SdpSessionDescription description, String trackId) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null || media.mediaType() == null) {
            return TrackType.UNKNOWN;
        }
        String mediaType = media.mediaType().trim().toLowerCase(Locale.ROOT);
        if ("video".equals(mediaType)) {
            return TrackType.VIDEO;
        }
        if ("audio".equals(mediaType)) {
            return TrackType.AUDIO;
        }
        if ("application".equals(mediaType) || "data".equals(mediaType)) {
            return TrackType.DATA;
        }
        return TrackType.UNKNOWN;
    }

    public static CodecType resolveCodecType(SdpSessionDescription description, String trackId) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null || media.codecName() == null) {
            return CodecType.UNKNOWN;
        }
        String codec = media.codecName().trim().toUpperCase(Locale.ROOT);
        if ("H264".equals(codec)) {
            return CodecType.H264;
        }
        if ("H265".equals(codec) || "HEVC".equals(codec)) {
            return CodecType.H265;
        }
        if ("AAC".equals(codec)) {
            return CodecType.AAC;
        }
        if ("MPEG4-GENERIC".equals(codec)) {
            return CodecType.MPEG4_GENERIC;
        }
        if ("OPUS".equals(codec)) {
            return CodecType.OPUS;
        }
        if ("PCMA".equals(codec) || "G711A".equals(codec)) {
            return CodecType.G711A;
        }
        if ("PCMU".equals(codec) || "G711U".equals(codec)) {
            return CodecType.G711U;
        }
        return CodecType.UNKNOWN;
    }

    public static SdpMediaDescription findMediaDescription(SdpSessionDescription description, String trackId) {
        if (description == null || description.mediaDescriptions().isEmpty()) {
            return null;
        }
        String normalizedTrackId = normalize(trackId);
        for (SdpMediaDescription media : description.mediaDescriptions()) {
            if (matchesTrack(normalize(media.control()), normalizedTrackId)) {
                return media;
            }
        }
        if (description.mediaDescriptions().size() == 1) {
            return description.mediaDescriptions().get(0);
        }
        return null;
    }

    public static String resolveConnectionAddress(SdpSessionDescription description, String trackId) {
        if (description == null) {
            return null;
        }
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media != null) {
            String mediaAddress = parseConnectionAddress(media.connection());
            if (mediaAddress != null) {
                return mediaAddress;
            }
        }
        return parseConnectionAddress(description.connection());
    }

    public static boolean hasOutOfBandParameterSets(SdpSessionDescription description, String trackId) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null || media.codecName() == null) {
            return false;
        }
        String codec = media.codecName().trim().toUpperCase(Locale.ROOT);
        if ("H265".equals(codec) || "HEVC".equals(codec)) {
            return hasValue(media.fmtpParameters().get("sprop-vps"))
                    && hasValue(media.fmtpParameters().get("sprop-sps"))
                    && hasValue(media.fmtpParameters().get("sprop-pps"));
        }
        if ("H264".equals(codec)) {
            return hasValue(media.fmtpParameters().get("sprop-parameter-sets"));
        }
        return false;
    }

    public static byte[] resolveH264Sps(SdpSessionDescription description, String trackId) {
        String[] parameterSets = splitSpropParameterSets(description, trackId);
        return parameterSets.length > 0 ? decodeBase64(parameterSets[0]) : null;
    }

    public static byte[] resolveH264Pps(SdpSessionDescription description, String trackId) {
        String[] parameterSets = splitSpropParameterSets(description, trackId);
        return parameterSets.length > 1 ? decodeBase64(parameterSets[1]) : null;
    }

    public static byte[] resolveH265Vps(SdpSessionDescription description, String trackId) {
        return decodeParameter(description, trackId, "sprop-vps");
    }

    public static byte[] resolveH265Sps(SdpSessionDescription description, String trackId) {
        return decodeParameter(description, trackId, "sprop-sps");
    }

    public static byte[] resolveH265Pps(SdpSessionDescription description, String trackId) {
        return decodeParameter(description, trackId, "sprop-pps");
    }

    public static byte[] resolveAacAudioSpecificConfig(SdpSessionDescription description, String trackId) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null || media.codecName() == null) {
            return null;
        }
        String codec = media.codecName().trim().toUpperCase(Locale.ROOT);
        if (!"AAC".equals(codec) && !"MPEG4-GENERIC".equals(codec)) {
            return null;
        }
        return decodeHex(media.fmtpParameters().get("config"));
    }

    public static int resolveAacSizeLength(SdpSessionDescription description, String trackId) {
        return parsePositiveIntFmtp(description, trackId, "sizelength", 13);
    }

    public static int resolveAacIndexLength(SdpSessionDescription description, String trackId) {
        return parsePositiveIntFmtp(description, trackId, "indexlength", 3);
    }

    public static int resolveAacIndexDeltaLength(SdpSessionDescription description, String trackId) {
        return parsePositiveIntFmtp(description, trackId, "indexdeltalength", 3);
    }

    private static boolean matchesTrack(String control, String trackId) {
        if (control.isEmpty() || trackId.isEmpty()) {
            return false;
        }
        return control.equals(trackId)
                || control.endsWith(trackId)
                || trackId.endsWith(control);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String parseConnectionAddress(String connection) {
        if (connection == null) {
            return null;
        }
        String normalized = connection.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String[] parts = normalized.split("\\s+");
        return parts.length == 0 ? null : parts[parts.length - 1];
    }

    private static boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String[] splitSpropParameterSets(SdpSessionDescription description, String trackId) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null) {
            return new String[0];
        }
        String value = media.fmtpParameters().get("sprop-parameter-sets");
        if (!hasValue(value)) {
            return new String[0];
        }
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i] == null ? "" : parts[i].trim();
        }
        return parts;
    }

    private static byte[] decodeParameter(SdpSessionDescription description, String trackId, String parameterName) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null) {
            return null;
        }
        return decodeBase64(media.fmtpParameters().get(parameterName));
    }

    private static byte[] decodeBase64(String value) {
        if (!hasValue(value)) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    private static byte[] decodeHex(String value) {
        if (!hasValue(value)) {
            return null;
        }
        String normalized = value.trim();
        if ((normalized.length() & 1) != 0) {
            return null;
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            try {
                bytes[i] = (byte) Integer.parseInt(normalized.substring(index, index + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private static int parsePositiveIntFmtp(SdpSessionDescription description, String trackId, String parameterName, int defaultValue) {
        SdpMediaDescription media = findMediaDescription(description, trackId);
        if (media == null) {
            return defaultValue;
        }
        String value = media.fmtpParameters().get(parameterName);
        if (!hasValue(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
