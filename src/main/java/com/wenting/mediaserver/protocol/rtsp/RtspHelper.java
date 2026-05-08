package com.wenting.mediaserver.protocol.rtsp;

import com.wenting.mediaserver.core.codec.rtsp.RtspRequestMessage;
import com.wenting.mediaserver.core.enums.StreamProtocol;
import com.wenting.mediaserver.core.enums.rtsp.RtspTransportMode;
import com.wenting.mediaserver.core.model.StreamKey;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RtspHelper {

    private static final Pattern INTERLEAVED_PATTERN = Pattern.compile("interleaved\\s*=\\s*(\\d+)\\s*-\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLIENT_PORT_PATTERN = Pattern.compile("client_port\\s*=\\s*(\\d+)\\s*-\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public static StreamKey parseStreamKey(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return new StreamKey(StreamProtocol.RTSP, "default", "stream");
        }
        String path = uri;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash + 1) : "";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            return new StreamKey(StreamProtocol.RTSP, parts[0], parts[1]);
        }
        if (parts.length == 1 && !parts[0].isEmpty()) {
            return new StreamKey(StreamProtocol.RTSP, "default", parts[0]);
        }
        return new StreamKey(StreamProtocol.RTSP, "default", "stream");
    }

    public static RtspTransportMode parseTransportMode(String transportHeader) {
        if (transportHeader == null) {
            return RtspTransportMode.UNKNOWN;
        }
        String normalized = transportHeader.toLowerCase(Locale.ROOT);
        if (normalized.contains("interleaved")) {
            return RtspTransportMode.RTP_TCP_INTERLEAVED;
        }
        if (normalized.contains("client_port") || normalized.contains("multicast") || normalized.contains("udp")) {
            return RtspTransportMode.RTP_UDP;
        }
        return RtspTransportMode.UNKNOWN;
    }

    public static RtspTransport parseTransport(String transportHeader) {
        RtspTransportMode mode = parseTransportMode(transportHeader);
        if (transportHeader == null) {
            return RtspTransport.unknown(null);
        }

        Integer interleavedRtpChannel = null;
        Integer interleavedRtcpChannel = null;
        Integer clientRtpPort = null;
        Integer clientRtcpPort = null;

        Matcher interleavedMatcher = INTERLEAVED_PATTERN.matcher(transportHeader);
        if (interleavedMatcher.find()) {
            interleavedRtpChannel = parseInt(interleavedMatcher.group(1));
            interleavedRtcpChannel = parseInt(interleavedMatcher.group(2));
        }

        Matcher clientPortMatcher = CLIENT_PORT_PATTERN.matcher(transportHeader);
        if (clientPortMatcher.find()) {
            clientRtpPort = parseInt(clientPortMatcher.group(1));
            clientRtcpPort = parseInt(clientPortMatcher.group(2));
        }

        return new RtspTransport(
                mode,
                clientRtpPort,
                clientRtcpPort,
                null,
                null,
                interleavedRtpChannel,
                interleavedRtcpChannel,
                transportHeader
        );
    }

    public static String parseTrackId(String uri, StreamKey streamKey) {
        if (uri == null || uri.trim().isEmpty() || streamKey == null) {
            return "";
        }
        String path = uri;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash + 1) : "";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String prefix = streamKey.app() + "/" + streamKey.stream();
        if (!path.startsWith(prefix)) {
            return path;
        }
        String remainder = path.substring(prefix.length());
        while (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        return remainder;
    }

    public static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long estimateRequestBytes(RtspRequestMessage request) {
        long size = 0;
        size += safeLength(request.method()) + 1;
        size += safeLength(request.uri()) + 1;
        size += safeLength(request.version()) + 2;
        for (java.util.Map.Entry<String, String> header : request.headers().entrySet()) {
            size += safeLength(header.getKey()) + 2 + safeLength(header.getValue()) + 2;
        }
        size += 2;
        size += request.body() == null ? 0 : request.body().readableBytes();
        return size;
    }

    public static long estimateResponseBytes(FullHttpResponse response) {
        long size = 0;
        size += safeLength(response.protocolVersion().text()) + 1;
        size += safeLength(String.valueOf(response.status().code())) + 1;
        size += safeLength(response.status().reasonPhrase()) + 2;
        for (java.util.Map.Entry<String, String> header : response.headers()) {
            size += safeLength(header.getKey()) + 2 + safeLength(header.getValue()) + 2;
        }
        size += 2;
        size += response.content().readableBytes();
        return size;
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

}
