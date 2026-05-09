package com.wenting.mediaserver.protocol.http.flv;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

final class FlvWriter {

    private static final byte[] FLV_HEADER = new byte[] {
            'F', 'L', 'V',
            0x01,
            0x05,
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00
    };

    private final Channel channel;
    private boolean responseStarted;

    FlvWriter(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        this.channel = channel;
    }

    boolean isActive() {
        return channel.isActive();
    }

    boolean responseStarted() {
        return responseStarted;
    }

    void startHttpFlvResponse() {
        if (!isActive() || responseStarted) {
            return;
        }
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "video/x-flv");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.PRAGMA, "no-cache");
        response.headers().set("Access-Control-Allow-Origin", "*");
        HttpUtil.setTransferEncodingChunked(response, true);
        channel.write(response);
        channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(FLV_HEADER)));
        responseStarted = true;
    }

    void writeTag(byte tagType, byte[] payload, long timestamp) {
        if (!isActive()) {
            return;
        }
        startHttpFlvResponse();
        channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(FlvTagBuilder.build(tagType, payload, timestamp))));
    }
}
