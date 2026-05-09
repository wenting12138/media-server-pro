package com.wenting.mediaserver.core.remux.rtp;

import com.wenting.mediaserver.core.enums.publish.CodecType;

public final class RtpPayloadTypeResolver {

    private RtpPayloadTypeResolver() {
    }

    public static int resolve(CodecType codecType) {
        if (codecType == CodecType.H264) {
            return 96;
        }
        if (codecType == CodecType.AAC || codecType == CodecType.MPEG4_GENERIC) {
            return 97;
        }
        if (codecType == CodecType.H265) {
            return 98;
        }
        if (codecType == CodecType.G711A) {
            return 8;
        }
        if (codecType == CodecType.G711U) {
            return 0;
        }
        return 96;
    }
}
