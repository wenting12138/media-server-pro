package com.wenting.mediaserver.core.remux.rtp;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.StreamKey;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.publish.PublishedTrackContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Base64;

public final class RtspSdpDescriptionBuilder {

    private final AvcDecoderConfigurationRecordParser avcConfigParser = new AvcDecoderConfigurationRecordParser();
    private final HevcDecoderConfigurationRecordParser hevcConfigParser = new HevcDecoderConfigurationRecordParser();
    private final AacAudioSpecificConfigParser aacConfigParser = new AacAudioSpecificConfigParser();

    public String build(StreamKey streamKey, Collection<PublishedTrackContext> trackContexts) {
        if (streamKey == null || trackContexts == null || trackContexts.isEmpty()) {
            return null;
        }
        List<PublishedTrackContext> ordered = new ArrayList<PublishedTrackContext>(trackContexts);
        Collections.sort(ordered, new Comparator<PublishedTrackContext>() {
            @Override
            public int compare(PublishedTrackContext left, PublishedTrackContext right) {
                int leftOrder = left != null && left.isVideoTrack() ? 0 : (left != null && left.isAudioTrack() ? 1 : 2);
                int rightOrder = right != null && right.isVideoTrack() ? 0 : (right != null && right.isAudioTrack() ? 1 : 2);
                if (leftOrder != rightOrder) {
                    return leftOrder - rightOrder;
                }
                String leftId = left == null ? "" : left.trackId();
                String rightId = right == null ? "" : right.trackId();
                return leftId.compareTo(rightId);
            }
        });
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 0 0 IN IP4 127.0.0.1\r\n");
        sdp.append("s=").append(streamKey.path()).append("\r\n");
        sdp.append("c=IN IP4 0.0.0.0\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=tool:media-server-pro\r\n");
        boolean appended = false;
        for (PublishedTrackContext context : ordered) {
            if (context == null) {
                continue;
            }
            String media = buildMediaDescription(context);
            if (media != null && !media.isEmpty()) {
                sdp.append(media);
                appended = true;
            }
        }
        return appended ? sdp.toString() : null;
    }

    private String buildMediaDescription(PublishedTrackContext context) {
        if (context.isVideoTrack()) {
            if (context.codecType() == CodecType.H264) {
                return buildH264Description(context);
            }
            if (context.codecType() == CodecType.H265) {
                return buildH265Description(context);
            }
        }
        if (context.isAudioTrack()) {
            if (context.codecType() == CodecType.AAC || context.codecType() == CodecType.MPEG4_GENERIC) {
                return buildAacDescription(context);
            }
            if (context.codecType() == CodecType.G711A) {
                return buildSimpleAudioDescription(context, 8, "PCMA", Math.max(context.clockRate() == null ? 8000 : context.clockRate().intValue(), 8000), 1);
            }
            if (context.codecType() == CodecType.G711U) {
                return buildSimpleAudioDescription(context, 0, "PCMU", Math.max(context.clockRate() == null ? 8000 : context.clockRate().intValue(), 8000), 1);
            }
        }
        return null;
    }

    private String buildH264Description(PublishedTrackContext context) {
        InboundMediaFrame configFrame = context.latestConfigFrame();
        if (configFrame == null) {
            return null;
        }
        AvcDecoderConfigurationRecord config = avcConfigParser.parse(configFrame.payload());
        if (config == null || config.spsList().isEmpty() || config.ppsList().isEmpty()) {
            return null;
        }
        int payloadType = RtpPayloadTypeResolver.resolve(CodecType.H264);
        return "m=video 0 RTP/AVP " + payloadType + "\r\n"
                + "a=rtpmap:" + payloadType + " H264/" + Math.max(context.clockRate() == null ? 90000 : context.clockRate().intValue(), 1) + "\r\n"
                + "a=fmtp:" + payloadType + " packetization-mode=1; sprop-parameter-sets="
                + joinBase64(config.spsList()) + "," + joinBase64(config.ppsList())
                + "; profile-level-id=" + config.profileLevelIdHex() + "\r\n"
                + "a=control:" + context.trackId() + "\r\n";
    }

    private String buildH265Description(PublishedTrackContext context) {
        InboundMediaFrame configFrame = context.latestConfigFrame();
        if (configFrame == null) {
            return null;
        }
        HevcDecoderConfigurationRecord config = hevcConfigParser.parse(configFrame.payload());
        if (config == null || config.vpsList().isEmpty() || config.spsList().isEmpty() || config.ppsList().isEmpty()) {
            return null;
        }
        int payloadType = RtpPayloadTypeResolver.resolve(CodecType.H265);
        return "m=video 0 RTP/AVP " + payloadType + "\r\n"
                + "a=rtpmap:" + payloadType + " H265/" + Math.max(context.clockRate() == null ? 90000 : context.clockRate().intValue(), 1) + "\r\n"
                + "a=fmtp:" + payloadType + " sprop-vps=" + joinBase64(config.vpsList())
                + "; sprop-sps=" + joinBase64(config.spsList())
                + "; sprop-pps=" + joinBase64(config.ppsList()) + "\r\n"
                + "a=control:" + context.trackId() + "\r\n";
    }

    private String buildAacDescription(PublishedTrackContext context) {
        InboundMediaFrame configFrame = context.latestConfigFrame();
        if (configFrame == null) {
            return null;
        }
        AacAudioSpecificConfig config = aacConfigParser.parse(configFrame.payload());
        if (config == null || config.sampleRate() <= 0) {
            return null;
        }
        int payloadType = RtpPayloadTypeResolver.resolve(CodecType.AAC);
        return "m=audio 0 RTP/AVP " + payloadType + "\r\n"
                + "a=rtpmap:" + payloadType + " MPEG4-GENERIC/" + config.sampleRate() + "/" + Math.max(config.channels(), 1) + "\r\n"
                + "a=fmtp:" + payloadType + " profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; config=" + config.configHex() + "\r\n"
                + "a=control:" + context.trackId() + "\r\n";
    }

    private String buildSimpleAudioDescription(PublishedTrackContext context, int payloadType, String codec, int sampleRate, int channels) {
        return "m=audio 0 RTP/AVP " + payloadType + "\r\n"
                + "a=rtpmap:" + payloadType + " " + codec + "/" + sampleRate + "/" + channels + "\r\n"
                + "a=control:" + context.trackId() + "\r\n";
    }

    private String joinBase64(List<byte[]> nals) {
        StringBuilder builder = new StringBuilder();
        Base64.Encoder encoder = Base64.getEncoder();
        for (int i = 0; i < nals.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(encoder.encodeToString(nals.get(i)));
        }
        return builder.toString();
    }
}
