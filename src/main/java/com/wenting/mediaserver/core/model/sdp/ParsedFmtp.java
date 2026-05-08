package com.wenting.mediaserver.core.model.sdp;

import java.util.Collections;
import java.util.Map;

final class ParsedFmtp {

    private final Integer payloadType;
    private final Map<String, String> parameters;

    ParsedFmtp(Integer payloadType, Map<String, String> parameters) {
        this.payloadType = payloadType;
        this.parameters = parameters == null ? Collections.<String, String>emptyMap() : parameters;
    }

    Integer payloadType() {
        return payloadType;
    }

    Map<String, String> parameters() {
        return parameters;
    }
}
