package com.wenting.mediaserver.core.model.sdp;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One {@code m=} media section plus its attributes.
 */
public final class SdpMediaDescription {

    private final String mediaType;
    private final int port;
    private final String transportProtocol;
    private final List<String> formats;
    private final Integer bandwidthAsKbps;
    private final String connection;
    private final Integer payloadType;
    private final String codecName;
    private final Integer clockRate;
    private final Integer channels;
    private final String control;
    private final Map<String, String> fmtpParameters;
    private final Map<String, String> attributes;

    public SdpMediaDescription(
            String mediaType,
            int port,
            String transportProtocol,
            List<String> formats,
            Integer bandwidthAsKbps,
            String connection,
            Integer payloadType,
            String codecName,
            Integer clockRate,
            Integer channels,
            String control,
            Map<String, String> fmtpParameters,
            Map<String, String> attributes
    ) {
        this.mediaType = mediaType;
        this.port = port;
        this.transportProtocol = transportProtocol;
        this.formats = formats == null ? Collections.<String>emptyList() : Collections.unmodifiableList(formats);
        this.bandwidthAsKbps = bandwidthAsKbps;
        this.connection = connection;
        this.payloadType = payloadType;
        this.codecName = codecName;
        this.clockRate = clockRate;
        this.channels = channels;
        this.control = control;
        this.fmtpParameters = fmtpParameters == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(fmtpParameters);
        this.attributes = attributes == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(attributes);
    }

    public String mediaType() {
        return mediaType;
    }

    public int port() {
        return port;
    }

    public String transportProtocol() {
        return transportProtocol;
    }

    public List<String> formats() {
        return formats;
    }

    public Integer bandwidthAsKbps() {
        return bandwidthAsKbps;
    }

    public String connection() {
        return connection;
    }

    public Integer payloadType() {
        return payloadType;
    }

    public String codecName() {
        return codecName;
    }

    public Integer clockRate() {
        return clockRate;
    }

    public Integer channels() {
        return channels;
    }

    public String control() {
        return control;
    }

    public Map<String, String> fmtpParameters() {
        return fmtpParameters;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
}
