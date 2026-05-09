package com.wenting.mediaserver.core.track;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;

public interface ITrack {
    String trackId();

    CodecType codecType();

    TrackType trackType();

    String connectionAddress();

    int clockRate();

    default boolean outOfBandParameterSetsReady() {
        return false;
    }

    default byte[] h264Sps() {
        return null;
    }

    default byte[] h264Pps() {
        return null;
    }

    default byte[] h265Vps() {
        return null;
    }

    default byte[] h265Sps() {
        return null;
    }

    default byte[] h265Pps() {
        return null;
    }
}
