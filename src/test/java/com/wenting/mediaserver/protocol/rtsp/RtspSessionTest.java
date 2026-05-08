package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.enums.publish.CodecType;
import com.wenting.mediaserver.core.enums.publish.TrackType;
import com.wenting.mediaserver.core.track.AudioTrack;
import com.wenting.mediaserver.core.track.ITrack;
import com.wenting.mediaserver.core.track.VideoTrack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspSessionTest {

    @Test
    void shouldBuildTrackListFromSdp() {
        String sdp = ""
                + "v=0\r\n"
                + "o=- 0 0 IN IP4 127.0.0.1\r\n"
                + "s=No Name\r\n"
                + "c=IN IP4 127.0.0.1\r\n"
                + "t=0 0\r\n"
                + "m=video 0 RTP/AVP 96\r\n"
                + "b=AS:2500\r\n"
                + "a=rtpmap:96 H264/90000\r\n"
                + "a=control:streamid=0\r\n"
                + "m=audio 0 RTP/AVP 97\r\n"
                + "b=AS:128\r\n"
                + "a=rtpmap:97 MPEG4-GENERIC/48000/2\r\n"
                + "a=control:streamid=1\r\n";

        RtspSession session = new RtspSession();
        session.sdpOrigin(sdp);

        assertTrue(session.hasTracks());
        assertEquals(2, session.trackList().size());

        ITrack video = session.findTrack("streamid=0");
        assertNotNull(video);
        assertInstanceOf(VideoTrack.class, video);
        assertEquals(TrackType.VIDEO, video.trackType());
        assertEquals(CodecType.H264, video.codecType());
        assertEquals(90000, video.clockRate());

        ITrack audio = session.findTrack("streamid=1");
        assertNotNull(audio);
        assertInstanceOf(AudioTrack.class, audio);
        assertEquals(TrackType.AUDIO, audio.trackType());
        assertEquals(CodecType.MPEG4_GENERIC, audio.codecType());
        assertEquals(48000, audio.clockRate());
        assertEquals(48000, ((AudioTrack) audio).sampleRate());
        assertEquals(2, ((AudioTrack) audio).channels());
        assertEquals(128000, ((AudioTrack) audio).bitrate());
    }
}
