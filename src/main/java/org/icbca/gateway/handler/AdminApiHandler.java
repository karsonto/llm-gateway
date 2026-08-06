package org.icbca.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.icbca.gateway.admin.AdminSessionStore;
import org.icbca.gateway.admin.AdminUsageQuery;
import org.icbca.gateway.auth.ApiKeyInfo;
import org.icbca.gateway.auth.SqliteApiKeyStore;
import org.icbca.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API under {@code /admin/api/*}. Requires SQLite-backed key store for mutations/queries.
 */
public final class AdminApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(AdminApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "/admin/api";

    private final GatewayConfig config;
    private final AdminSessionStore sessions;
    private final SqliteApiKeyStore sqliteKeyStore;
    private final AdminUsageQuery usageQuery;

    public AdminApiHandler(GatewayConfig config, AdminSessionStore sessions,
                           SqliteApiKeyStore sqliteKeyStore, AdminUsageQuery usageQuery) {
        super(false);
        this.config = config;
        this.sessions = sessions;
        this.sqliteKeyStore = sqliteKeyStore;
        this.usageQuery = usageQuery;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = PathWhitelistHandler.extractPath(request.uri());
        if (!path.startsWith(PREFIX)) {
            ctx.fireChannelRead(request);
            return;
        }

        try {
            if (HttpMethod.OPTIONS.equals(request.method())) {
                writeJson(ctx, HttpResponseStatus.OK, "{}");
                return;
            }

            String sub = path.substring(PREFIX.length());
            if (sub.isEmpty()) {
                sub = "/";
            }

            if ("/login".equals(sub) && HttpMethod.POST.equals(request.method())) {
                handleLogin(ctx, request);
                return;
            }

            if (!requireSqlite(ctx)) {
                return;
            }

            if ("/logout".equals(sub) && HttpMethod.POST.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                String token = extractBearer(request);
                sessions.invalidate(token);
                writeJson(ctx, HttpResponseStatus.OK, "{\"ok\":true}");
                return;
            }

            if ("/keys".equals(sub)) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                if (HttpMethod.GET.equals(request.method())) {
                    writeJson(ctx, HttpResponseStatus.OK, keysPageJson(request.uri()));
                    return;
                }
                if (HttpMethod.POST.equals(request.method())) {
                    handleCreateKey(ctx, request);
                    return;
                }
                if (HttpMethod.PATCH.equals(request.method())) {
                    handleUpdateKey(ctx, request);
                    return;
                }
                writeError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                        "GET/POST/PATCH only");
                return;
            }

            if ("/groups".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                writeJson(ctx, HttpResponseStatus.OK, stringListJson(sqliteKeyStore.listGroupNames()));
                return;
            }

            if ("/usage/by-key".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                Map<String, String> q = parseQuery(request.uri());
                writeJson(ctx, HttpResponseStatus.OK,
                        usageQuery.queryByKeyJson(q.get("api_key"), q.get("from"), q.get("to")));
                return;
            }

            if ("/usage/by-group".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                Map<String, String> q = parseQuery(request.uri());
                writeJson(ctx, HttpResponseStatus.OK,
                        usageQuery.queryByGroupJson(q.get("group_name"), q.get("from"), q.get("to")));
                return;
            }

            if ("/usage/rank".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                Map<String, String> q = parseQuery(request.uri());
                int limit = 10;
                String limitRaw = q.get("limit");
                if (limitRaw != null && !limitRaw.isEmpty()) {
                    try {
                        limit = Integer.parseInt(limitRaw.trim());
                    } catch (NumberFormatException ignored) {
                        limit = 10;
                    }
                }
                writeJson(ctx, HttpResponseStatus.OK,
                        usageQuery.queryRankJson(q.get("from"), q.get("to"), limit));
                return;
            }

            if ("/usage/latency".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                Map<String, String> q = parseQuery(request.uri());
                writeJson(ctx, HttpResponseStatus.OK,
                        usageQuery.queryLatencyByModelJson(
                                q.get("from"), q.get("to"), q.get("model")));
                return;
            }

            if ("/overview".equals(sub) && HttpMethod.GET.equals(request.method())) {
                if (!requireAuth(ctx, request)) {
                    return;
                }
                writeJson(ctx, HttpResponseStatus.OK, usageQuery.queryOverviewJson());
                return;
            }

            writeError(ctx, HttpResponseStatus.NOT_FOUND, "not_found", "Unknown admin API path");
        } finally {
            request.release();
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!config.isSqliteEnabled() || sqliteKeyStore == null) {
            writeError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "sqlite_required",
                    "Admin console requires gateway.sqlite.path");
            return;
        }
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonNode root = MAPPER.readTree(body == null || body.isEmpty() ? "{}" : body);
            String username = text(root, "username");
            String password = text(root, "password");
            if (config.getAdminUsername().equals(username)
                    && config.getAdminPassword().equals(password)) {
                String token = sessions.createSession();
                writeJson(ctx, HttpResponseStatus.OK,
                        "{\"token\":\"" + escape(token) + "\",\"username\":\""
                                + escape(username) + "\"}");
            } else {
                writeError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid_credentials",
                        "Invalid username or password");
            }
        } catch (Exception e) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, "bad_request", e.getMessage());
        }
    }

    private void handleCreateKey(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            JsonNode root = MAPPER.readTree(
                    request.content().toString(CharsetUtil.UTF_8));
            String apiKey = text(root, "api_key");
            String name = text(root, "name");
            String group = text(root, "group_name");
            boolean enabled = !root.has("enabled") || root.get("enabled").asBoolean(true);
            long monthlyLimit = 0L;
            if (root.has("monthly_token_limit") && !root.get("monthly_token_limit").isNull()) {
                monthlyLimit = Math.max(0L, root.get("monthly_token_limit").asLong(0L));
            }
            ApiKeyInfo info = sqliteKeyStore.create(apiKey, name, group, enabled, monthlyLimit);
            writeJson(ctx, HttpResponseStatus.OK, keyToJson(info, 0L));
        } catch (SQLException e) {
            writeError(ctx, HttpResponseStatus.CONFLICT, "create_failed", e.getMessage());
        } catch (Exception e) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, "bad_request", e.getMessage());
        }
    }

    private void handleUpdateKey(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            JsonNode root = MAPPER.readTree(
                    request.content().toString(CharsetUtil.UTF_8));
            String apiKey = text(root, "api_key");
            String name = root.has("name") ? text(root, "name") : null;
            String group = root.has("group_name") ? text(root, "group_name") : null;
            Boolean enabled = root.has("enabled") ? Boolean.valueOf(root.get("enabled").asBoolean()) : null;
            Long monthlyLimit = null;
            if (root.has("monthly_token_limit") && !root.get("monthly_token_limit").isNull()) {
                monthlyLimit = Long.valueOf(Math.max(0L, root.get("monthly_token_limit").asLong(0L)));
            }
            ApiKeyInfo info = sqliteKeyStore.update(apiKey, name, group, enabled, monthlyLimit);
            long used = usageQuery.sumTotalTokensForCurrentMonth(info.getKey());
            writeJson(ctx, HttpResponseStatus.OK, keyToJson(info, used));
        } catch (SQLException e) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, "update_failed", e.getMessage());
        } catch (Exception e) {
            writeError(ctx, HttpResponseStatus.BAD_REQUEST, "bad_request", e.getMessage());
        }
    }

    private boolean requireSqlite(ChannelHandlerContext ctx) {
        if (!config.isSqliteEnabled() || sqliteKeyStore == null || usageQuery == null) {
            writeError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "sqlite_required",
                    "Admin console requires gateway.sqlite.path");
            return false;
        }
        return true;
    }

    private boolean requireAuth(ChannelHandlerContext ctx, FullHttpRequest request) {
        String token = extractBearer(request);
        if (!sessions.isValid(token)) {
            writeError(ctx, HttpResponseStatus.UNAUTHORIZED, "unauthorized", "Login required");
            return false;
        }
        return true;
    }

    private static String extractBearer(FullHttpRequest request) {
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asText();
    }

    private static Map<String, String> parseQuery(String uri) {
        java.util.HashMap<String, String> map = new java.util.HashMap<String, String>();
        if (uri == null) {
            return map;
        }
        int q = uri.indexOf('?');
        if (q < 0) {
            return map;
        }
        for (String part : uri.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = urlDecode(part.substring(0, eq));
                String v = urlDecode(part.substring(eq + 1));
                map.put(k, v);
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String keysPageJson(String uri) {
        Map<String, String> q = parseQuery(uri);
        String search = blankToNull(q.get("q"));
        String group = blankToNull(q.get("group"));
        Boolean enabled = parseEnabled(q.get("enabled"));
        int page = parsePositiveInt(q.get("page"), 1);
        int pageSize = parsePositiveInt(q.get("page_size"), 20);
        if (pageSize > 100) {
            pageSize = 100;
        }
        int offset = (page - 1) * pageSize;
        int total = sqliteKeyStore.countPage(search, group, enabled);
        List<ApiKeyInfo> items = sqliteKeyStore.listPage(search, group, enabled, offset, pageSize);
        Map<String, Long> monthUsed = usageQuery.sumTotalTokensByKeyForCurrentMonth();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"total\":").append(total)
                .append(",\"page\":").append(page)
                .append(",\"page_size\":").append(pageSize)
                .append(",\"items\":").append(keysToJson(items, monthUsed))
                .append('}');
        return sb.toString();
    }

    private static String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s.trim();
    }

    private static Boolean parseEnabled(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String v = raw.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static int parsePositiveInt(String raw, int defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n < 1 ? defaultValue : n;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String keysToJson(List<ApiKeyInfo> list, Map<String, Long> monthUsed) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ApiKeyInfo info = list.get(i);
            long used = 0L;
            if (monthUsed != null && info.getKey() != null) {
                Long v = monthUsed.get(info.getKey());
                if (v != null) {
                    used = v.longValue();
                }
            }
            sb.append(keyToJson(info, used));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String keyToJson(ApiKeyInfo info, long monthUsedTokens) {
        return "{\"api_key\":\"" + escape(info.getKey())
                + "\",\"name\":\"" + escape(info.getName())
                + "\",\"group_name\":\"" + escape(info.getGroupName())
                + "\",\"enabled\":" + info.isEnabled()
                + ",\"monthly_token_limit\":" + info.getMonthlyTokenLimit()
                + ",\"month_used_tokens\":" + monthUsedTokens
                + "}";
    }

    private static String stringListJson(List<String> list) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(list.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        ByteBuf content = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PATCH, OPTIONS");
        HttpUtil.setContentLength(response, content.readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void writeError(ChannelHandlerContext ctx, HttpResponseStatus status,
                                   String code, String message) {
        writeJson(ctx, status, "{\"error\":{\"code\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message == null ? "" : message) + "\"}}");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("AdminApiHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
