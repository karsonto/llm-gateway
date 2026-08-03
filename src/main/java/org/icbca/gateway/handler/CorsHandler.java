package org.icbca.gateway.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

/**
 * Global CORS: short-circuit OPTIONS preflight (no auth) and inject CORS headers on outbound responses.
 */
public final class CorsHandler extends ChannelDuplexHandler {

    private static final String ALLOW_ORIGIN = "*";
    private static final String ALLOW_METHODS = "GET, POST, PATCH, OPTIONS";
    private static final String ALLOW_HEADERS = "Authorization, Content-Type, X-API-Key, Classification";
    private static final String MAX_AGE = "86400";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            if (HttpMethod.OPTIONS.equals(request.method())) {
                ReferenceCountUtil.release(request);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
                applyCorsHeaders(response);
                HttpUtil.setContentLength(response, 0);
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            applyCorsHeaders((HttpResponse) msg);
        }
        ctx.write(msg, promise);
    }

    private static void applyCorsHeaders(HttpResponse response) {
        if (!response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOW_ORIGIN);
        }
        if (!response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS)) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, ALLOW_METHODS);
        }
        if (!response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, ALLOW_HEADERS);
        }
        if (!response.headers().contains(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE)) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, MAX_AGE);
        }
    }
}
