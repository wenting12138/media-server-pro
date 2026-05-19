package com.wenting.mediaserver.protocol.webrtc.util;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.publish.InboundMediaFrame;
import com.wenting.mediaserver.core.remux.rtp.RtpPayloadTypeResolver;
import com.wenting.mediaserver.protocol.webrtc.api.MediaStreamTrack;
import com.wenting.mediaserver.protocol.webrtc.api.RTCRtpTransceiver;

public class WebrtcPlayUtil {

    public static int defaultClockRate(InboundMediaFrame frame) {
        if (frame.trackType() == TrackType.VIDEO) {
            return 90000;
        }
        if (frame.codecType() == CodecType.G711A || frame.codecType() == CodecType.G711U) {
            return 8000;
        }
        return 48000;
    }

    public static int resolvePayloadType(RTCRtpTransceiver transceiver, CodecType codecType) {
        if (transceiver != null && transceiver.getNegotiatedPayloadType() != null) {
            return transceiver.getNegotiatedPayloadType().intValue();
        }
        return RtpPayloadTypeResolver.resolve(codecType);
    }

    public static int resolveClockRate(RTCRtpTransceiver transceiver, InboundMediaFrame frame) {
        if (transceiver != null && transceiver.getNegotiatedClockRate() != null && transceiver.getNegotiatedClockRate().intValue() > 0) {
            return transceiver.getNegotiatedClockRate().intValue();
        }
        return defaultClockRate(frame);
    }

    public static Long mediaTimestampMillis(InboundMediaFrame frame) {
        if (frame == null) {
            return null;
        }
        return frame.ptsMillis() == null ? frame.dtsMillis() : frame.ptsMillis();
    }

    public static String normalizeTrackId(String trackId) {
        return trackId == null ? "" : trackId.trim();
    }

    public static boolean matchesKind(RTCRtpTransceiver transceiver, InboundMediaFrame frame) {
        if (frame.trackType() == TrackType.VIDEO) {
            return transceiver.getKind() == MediaStreamTrack.Kind.VIDEO;
        }
        if (frame.trackType() == TrackType.AUDIO) {
            return transceiver.getKind() == MediaStreamTrack.Kind.AUDIO;
        }
        return false;
    }

    public static boolean canSend(RTCRtpTransceiver transceiver) {
        return transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDONLY
                || transceiver.getDirection() == RTCRtpTransceiver.Direction.SENDRECV;
    }


}
