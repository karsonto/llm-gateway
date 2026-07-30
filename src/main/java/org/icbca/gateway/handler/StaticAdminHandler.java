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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Serves the admin SPA from {@code classpath:static/admin/} under {@code /admin}.
 */
public final class StaticAdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(StaticAdminHandler.class);
    private static final String PREFIX = "/admin";
    private static final String CLASSPATH_ROOT = "static/admin";

    public StaticAdminHandler() {
        super(false);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = PathWhitelistHandler.extractPath(request.uri());
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) {
            ctx.fireChannelRead(request);
            return;
        }
        // /admin/api is handled earlier; if it reaches here, ignore
        if (path.startsWith(PREFIX + "/api")) {
            ctx.fireChannelRead(request);
            return;
        }

        try {
            if (!HttpMethod.GET.equals(request.method()) && !HttpMethod.HEAD.equals(request.method())) {
                writeText(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "text/plain", "GET only");
                return;
            }

            String resourcePath = resolveResource(path);
            byte[] data = readClasspath(resourcePath);
            if (data == null && !resourcePath.endsWith("index.html")) {
                // SPA fallback
                data = readClasspath(CLASSPATH_ROOT + "/index.html");
            }
            if (data == null) {
                writeText(ctx, HttpResponseStatus.NOT_FOUND, "text/plain; charset=UTF-8",
                        "Admin UI not found. Build admin-web into src/main/resources/static/admin/");
                return;
            }

            String contentType = contentTypeOf(resourcePath);
            ByteBuf content = Unpooled.wrappedBuffer(data);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    HttpMethod.HEAD.equals(request.method()) ? Unpooled.EMPTY_BUFFER : content);
            if (HttpMethod.HEAD.equals(request.method())) {
                content.release();
            }
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            HttpUtil.setContentLength(response,
                    HttpMethod.HEAD.equals(request.method()) ? data.length : response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL,
                    resourcePath.endsWith("index.html") ? "no-cache" : "public, max-age=86400");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } finally {
            request.release();
        }
    }

    private static String resolveResource(String path) {
        String relative = path.substring(PREFIX.length());
        if (relative.isEmpty() || "/".equals(relative)) {
            return CLASSPATH_ROOT + "/index.html";
        }
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        // prevent path traversal
        if (relative.contains("..")) {
            return CLASSPATH_ROOT + "/index.html";
        }
        return CLASSPATH_ROOT + "/" + relative;
    }

    private static byte[] readClasspath(String resourcePath) {
        InputStream in = StaticAdminHandler.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("failed to read {}: {}", resourcePath, e.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static String contentTypeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (p.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (p.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (p.endsWith(".json")) {
            return "application/json";
        }
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (p.endsWith(".woff")) {
            return "font/woff";
        }
        return "application/octet-stream";
    }

    private static void writeText(ChannelHandlerContext ctx, HttpResponseStatus status,
                                  String contentType, String body) {
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("StaticAdminHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
