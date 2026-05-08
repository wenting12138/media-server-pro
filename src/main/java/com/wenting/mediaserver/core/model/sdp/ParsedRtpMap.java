package com.wenting.mediaserver.core.model.sdp;

final class ParsedRtpMap {

    private final Integer payloadType;
    private final String codecName;
    private final Integer clockRate;
    private final Integer channels;

    ParsedRtpMap(Integer payloadType, String codecName, Integer clockRate, Integer channels) {
        this.payloadType = payloadType;
        this.codecName = codecName;
        this.clockRate = clockRate;
        this.channels = channels;
    }

    Integer payloadType() {
        return payloadType;
    }

    String codecName() {
        return codecName;
    }

    Integer clockRate() {
        return clockRate;
    }

    Integer channels() {
        return channels;
    }
}
