package org.icbca.gateway.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.icbca.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects requests whose path is not in the configured whitelist.
 */
public final class PathWhitelistHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(PathWhitelistHandler.class);

    private final GatewayConfig config;

    public PathWhitelistHandler(GatewayConfig config) {
        super(false);
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = extractPath(request.uri());
        if (!config.isPathAllowed(path)) {
            log.info("path not whitelisted: {}", path);
            writeJsonError(ctx, HttpResponseStatus.FORBIDDEN, "path_not_allowed",
                    "Path not in whitelist: " + path);
            request.release();
            return;
        }
        ctx.fireChannelRead(request);
    }

    static String extractPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        if (path.isEmpty()) {
            return "/";
        }
        return path;
    }

    static void writeJsonError(ChannelHandlerContext ctx, HttpResponseStatus status,
                               String code, String message) {
        String body = "{\"error\":{\"code\":\"" + escapeJson(code)
                + "\",\"message\":\"" + escapeJson(message) + "\"}}";
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("PathWhitelistHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
