package com.wenting.mediaserver.protocol.webrtc;

import com.wenting.mediaserver.protocol.webrtc.api.*;
import com.wenting.mediaserver.protocol.webrtc.core.sdp.*;
import org.junit.Test;

public class DebugSdp {
    @Test
    public void printOfferSdp() throws Exception {
        RTCPeerConnection pc = new RTCPeerConnection();
        pc.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.AUDIO, "audio1"));
        pc.addTrack(new MediaStreamTrack(MediaStreamTrack.Kind.VIDEO, "video1"));
        RTCSessionDescription offer = pc.createOffer().get();
        String sdp = offer.getSdp();
        System.out.println("=== OFFER SDP ===");
        System.out.println(sdp);
        System.out.println("=== PARSED ===");
        SdpDescription parsed = SdpParser.parse(sdp);
        for (SdpDescription.MediaDescription md : parsed.getMediaDescriptions()) {
            System.out.println("Media: " + md.mediaType + " mid=" + md.getMid() + " ssrc=" + md.getSsrc());
            for (SdpDescription.Attribute a : md.attributes) {
                System.out.println("  a=" + a.key + " val=" + a.value);
            }
        }
        pc.close();
    }
}
