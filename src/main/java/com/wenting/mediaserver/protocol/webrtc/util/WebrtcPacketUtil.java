package com.wenting.mediaserver.protocol.webrtc.util;

public class WebrtcPacketUtil {

    public static boolean isDtlsPacket(byte[] data) {
        return data.length > 0 && (data[0] & 0xFF) >= 20 && (data[0] & 0xFF) <= 63;
    }

    public static boolean isRtpPacket(byte[] data) {
        // RTP version 2: first byte has bits 7-6 = 10 (0x80-0xBF)
        return data.length >= 12 && (data[0] & 0xC0) == 0x80;
    }

    public static boolean isRtcpPacket(byte[] data) {
        return data.length >= 8 && (data[0] & 0xC0) == 0x80 && (data[1] & 0xFF) >= 192 && (data[1] & 0xFF) <= 223;
    }

    public static boolean isStunPacket(byte[] data) {
        // STUN magic cookie at bytes 4-7: 0x2112A442
        return data.length >= 20
                && data[4] == 0x21 && data[5] == 0x12
                && data[6] == (byte) 0xA4 && data[7] == 0x42;
    }

}
