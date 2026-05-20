package com.wenting.mediaserver.protocol.http.utils;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import java.util.function.Consumer;

public final class HttpUtil {

    private HttpUtil() {
    }

    public static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        writeBytes(ctx, status, body.getBytes(CharsetUtil.UTF_8),
                "application/json; charset=UTF-8", null, true);
    }

    public static void writeBytes(ChannelHandlerContext ctx,
                                  HttpResponseStatus status,
                                  byte[] content,
                                  String contentType,
                                  boolean closeAfterWrite) {
        writeBytes(ctx, status, content, contentType, null, closeAfterWrite);
    }

    public static void writeBytes(ChannelHandlerContext ctx,
                                  HttpResponseStatus status,
                                  byte[] content,
                                  String contentType,
                                  Consumer<DefaultFullHttpResponse> responseCustomizer,
                                  boolean closeAfterWrite) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(content)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
        if (responseCustomizer != null) {
            responseCustomizer.accept(response);
        }
        if (closeAfterWrite) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.writeAndFlush(response);
    }

    public static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }
}
