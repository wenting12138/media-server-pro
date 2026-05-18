package com.wenting.mediaserver.core.track;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.model.sdp.SdpMediaDescription;
import com.wenting.mediaserver.core.model.sdp.SdpParser;
import com.wenting.mediaserver.core.model.sdp.SdpSessionDescription;
import com.wenting.mediaserver.core.publish.PublishedTrackMetadataResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SdpTrackMapper {

    private SdpTrackMapper() {
    }

    public static List<ITrack> map(SdpSessionDescription description) {
        if (description == null || description.mediaDescriptions().isEmpty()) {
            return Collections.emptyList();
        }
        List<ITrack> tracks = new ArrayList<ITrack>(description.mediaDescriptions().size());
        for (SdpMediaDescription media : description.mediaDescriptions()) {
            ITrack track = mapMedia(description, media);
            if (track != null) {
                tracks.add(track);
            }
        }
        return Collections.unmodifiableList(tracks);
    }

    private static ITrack mapMedia(SdpSessionDescription description, SdpMediaDescription media) {
        if (media == null) {
            return null;
        }
        String trackId = media.control() == null ? "" : media.control().trim();
        TrackType trackType = PublishedTrackMetadataResolver.resolveTrackType(description, trackId);
        CodecType codecType = PublishedTrackMetadataResolver.resolveCodecType(description, trackId);
        String connectionAddress = PublishedTrackMetadataResolver.resolveConnectionAddress(description, trackId);
        if (trackType == TrackType.VIDEO) {
            return new VideoTrack(
                    trackId,
                    codecType,
                    connectionAddress,
                    media.clockRate() == null ? 0 : media.clockRate().intValue(),
                    PublishedTrackMetadataResolver.hasOutOfBandParameterSets(description, trackId),
                    PublishedTrackMetadataResolver.resolveH264Sps(description, trackId),
                    PublishedTrackMetadataResolver.resolveH264Pps(description, trackId),
                    PublishedTrackMetadataResolver.resolveH265Vps(description, trackId),
                    PublishedTrackMetadataResolver.resolveH265Sps(description, trackId),
                    PublishedTrackMetadataResolver.resolveH265Pps(description, trackId)
            );
        }
        if (trackType == TrackType.AUDIO) {
            return new AudioTrack(
                    trackId,
                    codecType,
                    connectionAddress,
                    media.clockRate() == null ? 0 : media.clockRate().intValue(),
                    media.channels() == null ? 0 : media.channels().intValue(),
                    media.bandwidthAsKbps() == null ? 0 : media.bandwidthAsKbps().intValue() * 1000,
                    PublishedTrackMetadataResolver.resolveAacAudioSpecificConfig(description, trackId),
                    PublishedTrackMetadataResolver.resolveAacSizeLength(description, trackId),
                    PublishedTrackMetadataResolver.resolveAacIndexLength(description, trackId),
                    PublishedTrackMetadataResolver.resolveAacIndexDeltaLength(description, trackId)
            );
        }
        return null;
    }

    private static SdpSessionDescription singleMediaDescription(SdpMediaDescription media) {
        return new SdpSessionDescription(
                null,
                null,
                null,
                media.connection(),
                null,
                null,
                Collections.singletonList(media)
        );
    }
}
