package com.wenting.mediaserver.protocol.webrtc.util;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.ice.CandidateType;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceCandidate;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpBuilder;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

public class WebrtcSdpUtil {

    private static Logger LOG = LoggerFactory.getLogger(WebrtcSdpUtil.class);
    private static final char[] CREDENTIAL_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    // ========================================================================
    // Utility — random credential generation
    // ========================================================================

    public static String generateCredential(SecureRandom random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = CREDENTIAL_CHARS[random.nextInt(CREDENTIAL_CHARS.length)];
        }
        return new String(chars);
    }


    public static boolean hasSctpMedia(SdpDescription sdp) {
        if (sdp == null) {
            return false;
        }
        for (SdpDescription.MediaDescription md : sdp.getMediaDescriptions()) {
            if (md == null || md.mediaType == null || md.protocol == null) {
                continue;
            }
            if ("application".equals(md.mediaType)
                    && md.port > 0
                    && md.protocol.toUpperCase(Locale.ROOT).contains("SCTP")) {
                return true;
            }
        }
        return false;
    }

    public static RTCRtpTransceiver.Direction extractDirection(SdpDescription.MediaDescription md) {
        for (SdpDescription.Attribute attr : md.attributes) {
            if (attr.value == null) {
                if ("sendrecv".equals(attr.key)) return RTCRtpTransceiver.Direction.SENDRECV;
                if ("sendonly".equals(attr.key)) return RTCRtpTransceiver.Direction.SENDONLY;
                if ("recvonly".equals(attr.key)) return RTCRtpTransceiver.Direction.RECVONLY;
                if ("inactive".equals(attr.key)) return RTCRtpTransceiver.Direction.INACTIVE;
            }
        }
        return RTCRtpTransceiver.Direction.RECVONLY;
    }

    public static RTCRtpTransceiver.Direction invertDirection(RTCRtpTransceiver.Direction dir) {
        switch (dir) {
            case SENDONLY: return RTCRtpTransceiver.Direction.RECVONLY;
            case RECVONLY: return RTCRtpTransceiver.Direction.SENDONLY;
            case SENDRECV: return RTCRtpTransceiver.Direction.RECVONLY;
            default: return RTCRtpTransceiver.Direction.INACTIVE;
        }
    }

    public static String extractSetupAttribute(String sdpText) {
        if (sdpText == null || sdpText.trim().isEmpty()) {
            return null;
        }
        try {
            return extractSetupAttribute(SdpParser.parse(sdpText));
        } catch (Exception e) {
            LOG.debug("Failed to parse local SDP for setup attribute: {}", e.getMessage());
            return null;
        }
    }

    public static String extractSetupAttribute(SdpDescription sdp) {
        if (sdp == null) {
            return null;
        }
        for (SdpDescription.MediaDescription md : sdp.getMediaDescriptions()) {
            if (md == null || md.attributes == null) {
                continue;
            }
            for (SdpDescription.Attribute attr : md.attributes) {
                if ("setup".equals(attr.key) && attr.value != null) {
                    return attr.value.trim();
                }
            }
        }
        for (SdpDescription.Attribute attr : sdp.getSessionAttributes()) {
            if ("setup".equals(attr.key) && attr.value != null) {
                return attr.value.trim();
            }
        }
        return null;
    }

    public static CodecType parseCodecTypeFromRtpMap(String rtpMap, MediaStreamTrack.Kind kind) {
        if (rtpMap == null) {
            return kind == MediaStreamTrack.Kind.VIDEO ? CodecType.H264 : CodecType.UNKNOWN;
        }
        String[] parts = rtpMap.trim().split("\\s+", 2);
        if (parts.length < 2) {
            return kind == MediaStreamTrack.Kind.VIDEO ? CodecType.H264 : CodecType.UNKNOWN;
        }
        String codec = parts[1].toLowerCase(Locale.ROOT);
        if (codec.startsWith("h264/")) {
            return CodecType.H264;
        }
        if (codec.startsWith("opus/")) {
            return CodecType.OPUS;
        }
        if (codec.startsWith("pcmu/")) {
            return CodecType.G711U;
        }
        if (codec.startsWith("pcma/")) {
            return CodecType.G711A;
        }
        return CodecType.UNKNOWN;
    }

    public static int parseClockRateFromRtpMap(String rtpMap, MediaStreamTrack.Kind kind) {
        if (rtpMap == null) {
            return kind == MediaStreamTrack.Kind.AUDIO ? 48000 : 90000;
        }
        String[] parts = rtpMap.trim().split("\\s+");
        if (parts.length < 2) {
            return kind == MediaStreamTrack.Kind.AUDIO ? 48000 : 90000;
        }
        String[] codecParts = parts[1].split("/");
        if (codecParts.length < 2) {
            return kind == MediaStreamTrack.Kind.AUDIO ? 48000 : 90000;
        }
        try {
            return Integer.parseInt(codecParts[1]);
        } catch (NumberFormatException e) {
            return kind == MediaStreamTrack.Kind.AUDIO ? 48000 : 90000;
        }
    }

    public static void addDirectionAttribute(SdpBuilder.MediaBuilder media, RTCRtpTransceiver.Direction direction) {
        switch (direction) {
            case SENDRECV: media.addAttribute("sendrecv"); break;
            case SENDONLY: media.addAttribute("sendonly"); break;
            case RECVONLY: media.addAttribute("recvonly"); break;
            case INACTIVE: media.addAttribute("inactive"); break;
            default: media.addAttribute("inactive"); break;
        }
    }

    public static Integer answerPayloadType(SdpDescription.MediaDescription offeredMedia, MediaStreamTrack.Kind kind) {
        if (kind == MediaStreamTrack.Kind.AUDIO) {
            return findPayloadTypeByCodec(offeredMedia, "pcmu/");
        }
        Integer matched = findPayloadTypeByCodec(offeredMedia, "h264/");
        if (matched != null) {
            return matched;
        }
        return null;
    }

    public static String answerRtpMap(SdpDescription.MediaDescription offeredMedia, int payloadType, MediaStreamTrack.Kind kind) {
        String offered = findRtpMapValue(offeredMedia, payloadType);
        if (offered != null) {
            return offered;
        }
        if (kind == MediaStreamTrack.Kind.AUDIO && payloadType == 0) {
            return "0 PCMU/8000";
        }
        if (kind == MediaStreamTrack.Kind.AUDIO && payloadType == 8) {
            return "8 PCMA/8000";
        }
        return kind == MediaStreamTrack.Kind.AUDIO
                ? payloadType + " opus/48000/2"
                : payloadType + " H264/90000";
    }


    public static Integer findPayloadTypeByCodec(SdpDescription.MediaDescription media, String codecPrefixLowerCase) {
        for (SdpDescription.Attribute attr : media.attributes) {
            if (!"rtpmap".equals(attr.key) || attr.value == null) {
                continue;
            }
            String[] parts = attr.value.trim().split("\\s+", 2);
            if (parts.length < 2 || !parts[1].toLowerCase(Locale.ROOT).startsWith(codecPrefixLowerCase)) {
                continue;
            }
            try {
                return Integer.valueOf(Integer.parseInt(parts[0]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String findRtpMapValue(SdpDescription.MediaDescription media, int payloadType) {
        String prefix = String.valueOf(payloadType) + " ";
        for (SdpDescription.Attribute attr : media.attributes) {
            if ("rtpmap".equals(attr.key) && attr.value != null && attr.value.startsWith(prefix)) {
                return attr.value;
            }
        }
        return null;
    }

    public static int firstPayloadTypeOrDefault(SdpDescription.MediaDescription media) {
        if (!media.payloadTypes.isEmpty()) {
            return media.payloadTypes.get(0).intValue();
        }
        return "audio".equals(media.mediaType) ? 111 : 96;
    }

    public static int[] toPayloadTypeArray(List<Integer> payloadTypes) {
        int[] result = new int[payloadTypes == null ? 0 : payloadTypes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = payloadTypes.get(i).intValue();
        }
        return result;
    }

    public static String safeMid(SdpDescription.MediaDescription media) {
        String mid = media.getMid();
        return mid == null || mid.trim().isEmpty() ? "0" : mid;
    }

    public static void copyCodecAttributes(SdpDescription.MediaDescription offeredMedia, SdpBuilder.MediaBuilder media, int payloadType) {
        String ptPrefix = String.valueOf(payloadType) + " ";
        for (SdpDescription.Attribute attr : offeredMedia.attributes) {
            if (attr == null || attr.key == null || attr.value == null) {
                continue;
            }
            if ("fmtp".equals(attr.key) && attr.value.startsWith(ptPrefix)) {
                media.addAttribute("fmtp", attr.value);
                continue;
            }
            if ("rtcp-fb".equals(attr.key)
                    && (attr.value.startsWith(ptPrefix) || attr.value.startsWith("* "))) {
                media.addAttribute("rtcp-fb", attr.value);
            }
        }
    }


    public static void addCandidateAttributes(SdpBuilder.MediaBuilder media, IceAgent agent) {
        for (IceCandidate c : agent.getLocalCandidates()) {
            String relatedAddr = null;
            int relatedPort = 0;
            if (c.getRelatedAddress() != null) {
                relatedAddr = c.getRelatedAddress().getHostString();
                relatedPort = c.getRelatedAddress().getPort();
            }
            SdpBuilder.IceCandidateInfo ci = new SdpBuilder.IceCandidateInfo(
                    c.getFoundation(), c.getComponentId(), c.getTransport(),
                    c.getPriority(), c.getAddress().getHostString(),
                    c.getAddress().getPort(), IceCandidate.typeToString(c.getType()),
                    relatedAddr, relatedPort);
            media.addCandidate(ci);
        }
    }

    /**
     * Parse an SDP candidate attribute line into an IceCandidate.
     * Input: "a=candidate:foundation componentId transport priority ip port typ type ..."
     */
    public static IceCandidate parseCandidate(String sdpAttr, String ufrag) {
        try {
            String val = sdpAttr;
            if (val.startsWith("a=candidate:")) {
                val = val.substring("a=candidate:".length());
            } else if (val.startsWith("candidate:")) {
                val = val.substring("candidate:".length());
            }

            String[] parts = val.split(" ");
            if (parts.length < 8) return null;

            String foundation = parts[0];
            int componentId = Integer.parseInt(parts[1]);
            String transportType = parts[2];
            long priority = Long.parseLong(parts[3]);
            String ip = parts[4];
            int port = Integer.parseInt(parts[5]);

            // parts[6] should be "typ"
            CandidateType type = IceCandidate.stringToType(parts[7]);

            return new IceCandidate(foundation, componentId, transportType,
                    new InetSocketAddress(ip, port), type, null);
        } catch (Exception e) {
            LOG.warn("Failed to parse ICE candidate: " + e.getMessage());
            return null;
        }
    }

}
