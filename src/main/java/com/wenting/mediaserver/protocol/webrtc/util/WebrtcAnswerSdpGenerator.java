package com.wenting.mediaserver.protocol.webrtc.util;

import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;
import com.wenting.mediaserver.protocol.webrtc.core.ice.IceAgent;
import com.wenting.mediaserver.protocol.webrtc.core.sctp.SctpConstants;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpBuilder;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.SdpDescription;

import java.net.InetSocketAddress;
import java.util.List;

public class WebrtcAnswerSdpGenerator {

    public static String answerBundleMids(SdpDescription offer) {
        StringBuilder mids = new StringBuilder();
        for (SdpDescription.MediaDescription media : offer.getMediaDescriptions()) {
            String mid = media.getMid();
            if (mid == null || mid.trim().isEmpty()) {
                continue;
            }
            if (mids.length() > 0) {
                mids.append(' ');
            }
            mids.append(mid);
        }
        return mids.length() == 0 ? "0" : mids.toString();
    }

    public static void appendApplicationAnswer(SdpBuilder builder, SdpDescription.MediaDescription offeredMedia, IceAgent agent) {
        int[] payloadTypes = WebrtcSdpUtil.toPayloadTypeArray(offeredMedia.payloadTypes);
        if (payloadTypes.length == 0) {
            payloadTypes = new int[]{5000};
        }
        SdpBuilder.MediaBuilder appMedia = builder.addMedia(
                offeredMedia.mediaType,
                9,
                offeredMedia.protocol,
                payloadTypes);
        appMedia.addAttribute("mid", WebrtcSdpUtil.safeMid(offeredMedia));
        appMedia.addAttribute("setup", "passive");
        WebrtcSdpUtil.addCandidateAttributes(appMedia, agent);
        appMedia.addAttribute("sctp-port", String.valueOf(SctpConstants.DEFAULT_OS));
    }

    public static void appendMediaAnswer(List<RTCRtpTransceiver> transceivers, SdpBuilder builder, SdpDescription.MediaDescription offeredMedia, IceAgent agent) {
        RTCRtpTransceiver transceiver = findTransceiverByMid(transceivers, offeredMedia.getMid());
        if (transceiver == null) {
            SdpBuilder.MediaBuilder rejected = builder.addMedia(
                    offeredMedia.mediaType,
                    0,
                    offeredMedia.protocol,
                    WebrtcSdpUtil.firstPayloadTypeOrDefault(offeredMedia));
            rejected.addAttribute("mid", WebrtcSdpUtil.safeMid(offeredMedia));
            return;
        }

        Integer payloadType = WebrtcSdpUtil.answerPayloadType(offeredMedia, transceiver.getKind());
        if (payloadType == null) {
            transceiver.setNegotiatedPayloadType(null);
            transceiver.setNegotiatedClockRate(null);
            transceiver.setNegotiatedCodecType(null);
            transceiver.setDirection(RTCRtpTransceiver.Direction.INACTIVE);
            SdpBuilder.MediaBuilder rejected = builder.addMedia(
                    offeredMedia.mediaType,
                    0,
                    offeredMedia.protocol,
                    WebrtcSdpUtil.firstPayloadTypeOrDefault(offeredMedia));
            rejected.addAttribute("mid", WebrtcSdpUtil.safeMid(offeredMedia));
            return;
        }
        String rtpMap = WebrtcSdpUtil.answerRtpMap(offeredMedia, payloadType, transceiver.getKind());
        transceiver.setNegotiatedPayloadType(payloadType);
        transceiver.setNegotiatedClockRate(Integer.valueOf(WebrtcSdpUtil.parseClockRateFromRtpMap(rtpMap, transceiver.getKind())));
        transceiver.setNegotiatedCodecType(WebrtcSdpUtil.parseCodecTypeFromRtpMap(rtpMap, transceiver.getKind()));
        SdpBuilder.MediaBuilder media = builder.addMedia(offeredMedia.mediaType, 9, offeredMedia.protocol, payloadType);
        media.addAttribute("mid", WebrtcSdpUtil.safeMid(offeredMedia));
        media.addAttribute("rtpmap", rtpMap);
        WebrtcSdpUtil.copyCodecAttributes(offeredMedia, media, payloadType);
        media.addAttribute("rtcp-mux");
        media.addAttribute("setup", "passive");
        WebrtcSdpUtil.addDirectionAttribute(media, transceiver.getDirection());

        long ssrc = transceiver.getSender().getSsrc();
        media.addAttribute("ssrc", ssrc + " cname:webrtc-java");

        MediaStreamTrack track = transceiver.getSender().getTrack();
        if (track != null) {
            media.addAttribute("msid", WebrtcSdpUtil.safeMid(offeredMedia) + " " + track.getId());
        }

        WebrtcSdpUtil.addCandidateAttributes(media, agent);
    }

    public static RTCRtpTransceiver findTransceiverByMid(List<RTCRtpTransceiver> transceivers, String mid) {
        if (mid == null) {
            return null;
        }
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver != null && mid.equals(transceiver.getMid())) {
                return transceiver;
            }
        }
        return null;
    }

}
