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
import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.usage.ApiKeyUsageStats;
import org.icbca.gateway.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Serves usage query endpoints without proxying to upstream.
 * <ul>
 *   <li>{@code GET /v1/usage} — date/model rows for the caller's API key</li>
 *   <li>{@code GET /v1/admin/usage} — all keys, no auth</li>
 * </ul>
 */
public final class UsageQueryHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(UsageQueryHandler.class);

    public static final String PATH_USAGE = "/v1/usage";
    public static final String PATH_ADMIN_USAGE = "/v1/admin/usage";

    private final ApiKeyStore apiKeyStore;
    private final UsageRecorder usageRecorder;

    public UsageQueryHandler(ApiKeyStore apiKeyStore, UsageRecorder usageRecorder) {
        super(false);
        this.apiKeyStore = apiKeyStore;
        this.usageRecorder = usageRecorder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = PathWhitelistHandler.extractPath(request.uri());
        if (!PATH_USAGE.equals(path) && !PATH_ADMIN_USAGE.equals(path)) {
            ctx.fireChannelRead(request);
            return;
        }

        try {
            if (!HttpMethod.GET.equals(request.method())) {
                writeJsonError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                        "method_not_allowed", "Only GET is supported");
                return;
            }

            if (PATH_ADMIN_USAGE.equals(path)) {
                writeJson(ctx, HttpResponseStatus.OK, toStatsArrayJson(usageRecorder.getAllStats()));
                return;
            }

            // GET /v1/usage
            String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            String xApiKey = request.headers().get("X-API-Key");
            if (xApiKey == null) {
                xApiKey = request.headers().get("x-api-key");
            }
            String rawKey = ApiKeyStore.extractApiKey(authorization, xApiKey);

            if (apiKeyStore.isAuthRequired()) {
                if (rawKey == null || !apiKeyStore.isValid(rawKey)) {
                    writeJsonError(ctx, HttpResponseStatus.UNAUTHORIZED,
                            "invalid_api_key", "Missing or invalid API key");
                    return;
                }
                writeJson(ctx, HttpResponseStatus.OK, toStatsArrayJson(usageRecorder.getStats(rawKey)));
                return;
            }

            String key = rawKey != null ? rawKey : ApiKeyStore.ANONYMOUS_KEY;
            writeJson(ctx, HttpResponseStatus.OK, toStatsArrayJson(usageRecorder.getStats(key)));
        } finally {
            request.release();
        }
    }

    private static String toStatsJson(ApiKeyUsageStats s) {
        return "{\"api_key\":\"" + escapeJson(s.getApiKey())
                + "\",\"name\":\"" + escapeJson(s.getName())
                + "\",\"date\":\"" + escapeJson(s.getDate())
                + "\",\"model\":\"" + escapeJson(s.getModel())
                + "\",\"request_count\":" + s.getRequestCount()
                + ",\"prompt_tokens\":" + s.getPromptTokens()
                + ",\"completion_tokens\":" + s.getCompletionTokens()
                + ",\"total_tokens\":" + s.getTotalTokens()
                + "}";
    }

    private static String toStatsArrayJson(List<ApiKeyUsageStats> list) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toStatsJson(list.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void writeJsonError(ChannelHandlerContext ctx, HttpResponseStatus status,
                                       String code, String message) {
        String body = "{\"error\":{\"code\":\"" + escapeJson(code)
                + "\",\"message\":\"" + escapeJson(message == null ? "" : message) + "\"}}";
        writeJson(ctx, status, body);
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("UsageQueryHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
