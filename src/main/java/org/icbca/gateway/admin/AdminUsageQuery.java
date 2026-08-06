package org.icbca.gateway.admin;

import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds chart-oriented usage series from {@code usage_daily}.
 */
public final class AdminUsageQuery {

    private static final Logger log = LoggerFactory.getLogger(AdminUsageQuery.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SqliteDatabase db;
    private final ZoneId zoneId;

    public AdminUsageQuery(SqliteDatabase db) {
        this(db, ZoneId.systemDefault());
    }

    public AdminUsageQuery(SqliteDatabase db, ZoneId zoneId) {
        this.db = db;
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    /**
     * Current calendar month total_tokens per api_key.
     */
    public Map<String, Long> sumTotalTokensByKeyForCurrentMonth() {
        YearMonth ym = YearMonth.now(zoneId);
        final String from = ym.atDay(1).format(DAY);
        final String toExclusive = ym.plusMonths(1).atDay(1).format(DAY);
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<Map<String, Long>>() {
                @Override
                public Map<String, Long> run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT api_key, COALESCE(SUM(total_tokens), 0) AS tokens "
                                    + "FROM usage_daily WHERE usage_date >= ? AND usage_date < ? "
                                    + "GROUP BY api_key");
                    try {
                        ps.setString(1, from);
                        ps.setString(2, toExclusive);
                        ResultSet rs = ps.executeQuery();
                        try {
                            Map<String, Long> map = new HashMap<String, Long>();
                            while (rs.next()) {
                                map.put(rs.getString("api_key"), Long.valueOf(rs.getLong("tokens")));
                            }
                            return map;
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("sumTotalTokensByKeyForCurrentMonth failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Product overview dashboard payload (KPIs, trends, top users, quota alerts).
     */
    public String queryOverviewJson() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<String>() {
                @Override
                public String run(java.sql.Connection connection) throws SQLException {
                    LocalDate today = LocalDate.now(zoneId);
                    String todayStr = today.format(DAY);
                    YearMonth ym = YearMonth.from(today);
                    String monthFrom = ym.atDay(1).format(DAY);
                    String monthToExcl = ym.plusMonths(1).atDay(1).format(DAY);
                    String trendFrom = today.minusDays(6).format(DAY);

                    LocalDateTime nowHour = LocalDateTime.now(zoneId).withMinute(0).withSecond(0).withNano(0);
                    String latencyFrom = nowHour.minusHours(23).format(HOUR);

                    long[] todayTotals = queryTodayTotals(connection, todayStr);
                    long monthTokens = queryMonthTokens(connection, monthFrom, monthToExcl);
                    long activeUsers = queryActiveUsers(connection, todayStr);
                    List<TrendPoint> trend = queryTokenTrend(connection, trendFrom, todayStr);
                    List<LatencyPoint> latency = queryLatency24h(connection, latencyFrom);
                    List<TopUser> topUsers = queryTopUsers(connection, todayStr);
                    QuotaAlertResult alerts = queryQuotaAlerts(connection, monthFrom, monthToExcl);

                    String generatedAt = java.time.ZonedDateTime.now(zoneId).format(ISO_INSTANT);
                    StringBuilder sb = new StringBuilder(1024);
                    sb.append("{\"generated_at\":\"").append(escape(generatedAt)).append("\"")
                            .append(",\"kpis\":{")
                            .append("\"today_requests\":").append(todayTotals[0])
                            .append(",\"today_tokens\":").append(todayTotals[1])
                            .append(",\"month_tokens\":").append(monthTokens)
                            .append(",\"today_active_users\":").append(activeUsers)
                            .append(",\"quota_near_count\":").append(alerts.nearCount)
                            .append(",\"quota_exceeded_count\":").append(alerts.exceededCount)
                            .append('}');

                    sb.append(",\"token_trend_7d\":[");
                    for (int i = 0; i < trend.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        TrendPoint t = trend.get(i);
                        sb.append("{\"date\":\"").append(escape(t.date)).append("\"")
                                .append(",\"total_tokens\":").append(t.totalTokens)
                                .append(",\"request_count\":").append(t.requestCount)
                                .append('}');
                    }
                    sb.append(']');

                    sb.append(",\"latency_24h\":[");
                    for (int i = 0; i < latency.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        LatencyPoint p = latency.get(i);
                        sb.append("{\"bucket\":\"").append(escape(p.bucket)).append("\"")
                                .append(",\"avg_latency_ms\":").append(p.avgLatencyMs)
                                .append(",\"request_count\":").append(p.requestCount)
                                .append('}');
                    }
                    sb.append(']');

                    sb.append(",\"top_users_today\":[");
                    for (int i = 0; i < topUsers.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        TopUser u = topUsers.get(i);
                        sb.append("{\"name\":\"").append(escape(u.name)).append("\"")
                                .append(",\"group_name\":\"").append(escape(u.groupName)).append("\"")
                                .append(",\"total_tokens\":").append(u.totalTokens)
                                .append(",\"request_count\":").append(u.requestCount)
                                .append('}');
                    }
                    sb.append(']');

                    sb.append(",\"quota_alerts\":[");
                    for (int i = 0; i < alerts.rows.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        QuotaAlertRow r = alerts.rows.get(i);
                        sb.append("{\"api_key\":\"").append(escape(r.apiKey)).append("\"")
                                .append(",\"name\":\"").append(escape(r.name)).append("\"")
                                .append(",\"limit\":").append(r.limit)
                                .append(",\"used\":").append(r.used)
                                .append(",\"ratio\":").append(String.format(java.util.Locale.US, "%.4f", r.ratio))
                                .append(",\"status\":\"").append(escape(r.status)).append("\"")
                                .append('}');
                    }
                    sb.append("]}");
                    return sb.toString();
                }
            });
        } catch (SQLException e) {
            log.warn("queryOverview failed: {}", e.getMessage());
            return "{\"generated_at\":\"\",\"kpis\":{\"today_requests\":0,\"today_tokens\":0,"
                    + "\"month_tokens\":0,\"today_active_users\":0,\"quota_near_count\":0,"
                    + "\"quota_exceeded_count\":0},\"token_trend_7d\":[],\"latency_24h\":[],"
                    + "\"top_users_today\":[],\"quota_alerts\":[],\"error\":\""
                    + escape(e.getMessage()) + "\"}";
        }
    }

    private static long[] queryTodayTotals(java.sql.Connection connection, String today)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(request_count), 0), COALESCE(SUM(total_tokens), 0) "
                        + "FROM usage_daily WHERE usage_date = ?");
        try {
            ps.setString(1, today);
            ResultSet rs = ps.executeQuery();
            try {
                if (rs.next()) {
                    return new long[] { rs.getLong(1), rs.getLong(2) };
                }
                return new long[] { 0L, 0L };
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static long queryMonthTokens(java.sql.Connection connection, String from, String toExcl)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(total_tokens), 0) FROM usage_daily "
                        + "WHERE usage_date >= ? AND usage_date < ?");
        try {
            ps.setString(1, from);
            ps.setString(2, toExcl);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getLong(1) : 0L;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static long queryActiveUsers(java.sql.Connection connection, String today)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(DISTINCT COALESCE(k.name, u.api_key)) "
                        + "FROM usage_daily u LEFT JOIN api_keys k ON u.api_key = k.api_key "
                        + "WHERE u.usage_date = ?");
        try {
            ps.setString(1, today);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getLong(1) : 0L;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static List<TrendPoint> queryTokenTrend(java.sql.Connection connection,
                                                    String from, String toInclusive)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT usage_date, COALESCE(SUM(total_tokens), 0) AS total_tokens, "
                        + "COALESCE(SUM(request_count), 0) AS request_count "
                        + "FROM usage_daily WHERE usage_date >= ? AND usage_date <= ? "
                        + "GROUP BY usage_date ORDER BY usage_date ASC");
        try {
            ps.setString(1, from);
            ps.setString(2, toInclusive);
            ResultSet rs = ps.executeQuery();
            try {
                Map<String, TrendPoint> byDate = new LinkedHashMap<String, TrendPoint>();
                LocalDate start = LocalDate.parse(from);
                LocalDate end = LocalDate.parse(toInclusive);
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    String key = d.format(DAY);
                    byDate.put(key, new TrendPoint(key, 0L, 0L));
                }
                while (rs.next()) {
                    String date = rs.getString("usage_date");
                    byDate.put(date, new TrendPoint(
                            date, rs.getLong("total_tokens"), rs.getLong("request_count")));
                }
                return new ArrayList<TrendPoint>(byDate.values());
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static List<LatencyPoint> queryLatency24h(java.sql.Connection connection,
                                                      String fromBucket) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT hour_bucket, "
                        + "COALESCE(SUM(latency_sum_ms), 0) AS latency_sum_ms, "
                        + "COALESCE(SUM(request_count), 0) AS request_count "
                        + "FROM latency_hourly WHERE hour_bucket >= ? "
                        + "GROUP BY hour_bucket ORDER BY hour_bucket ASC");
        try {
            ps.setString(1, fromBucket);
            ResultSet rs = ps.executeQuery();
            try {
                List<LatencyPoint> list = new ArrayList<LatencyPoint>();
                while (rs.next()) {
                    long req = rs.getLong("request_count");
                    long sum = rs.getLong("latency_sum_ms");
                    long avg = req > 0 ? sum / req : 0L;
                    list.add(new LatencyPoint(rs.getString("hour_bucket"), avg, req));
                }
                return list;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static List<TopUser> queryTopUsers(java.sql.Connection connection, String today)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(k.name, u.api_key) AS user_name, "
                        + "COALESCE(MAX(k.group_name), 'default') AS group_name, "
                        + "COALESCE(SUM(u.total_tokens), 0) AS total_tokens, "
                        + "COALESCE(SUM(u.request_count), 0) AS request_count "
                        + "FROM usage_daily u LEFT JOIN api_keys k ON u.api_key = k.api_key "
                        + "WHERE u.usage_date = ? "
                        + "GROUP BY COALESCE(k.name, u.api_key) "
                        + "ORDER BY total_tokens DESC, request_count DESC LIMIT 10");
        try {
            ps.setString(1, today);
            ResultSet rs = ps.executeQuery();
            try {
                List<TopUser> list = new ArrayList<TopUser>();
                while (rs.next()) {
                    list.add(new TopUser(
                            rs.getString("user_name"),
                            rs.getString("group_name"),
                            rs.getLong("total_tokens"),
                            rs.getLong("request_count")));
                }
                return list;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static QuotaAlertResult queryQuotaAlerts(java.sql.Connection connection,
                                                     String monthFrom, String monthToExcl)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT k.api_key, k.name, k.monthly_token_limit, "
                        + "COALESCE(SUM(u.total_tokens), 0) AS used "
                        + "FROM api_keys k "
                        + "LEFT JOIN usage_daily u ON u.api_key = k.api_key "
                        + "AND u.usage_date >= ? AND u.usage_date < ? "
                        + "WHERE k.monthly_token_limit > 0 "
                        + "GROUP BY k.api_key, k.name, k.monthly_token_limit");
        try {
            ps.setString(1, monthFrom);
            ps.setString(2, monthToExcl);
            ResultSet rs = ps.executeQuery();
            try {
                List<QuotaAlertRow> all = new ArrayList<QuotaAlertRow>();
                int near = 0;
                int exceeded = 0;
                while (rs.next()) {
                    long limit = rs.getLong("monthly_token_limit");
                    long used = rs.getLong("used");
                    if (limit <= 0L) {
                        continue;
                    }
                    double ratio = (double) used / (double) limit;
                    String status;
                    if (ratio >= 1.0d) {
                        status = "exceeded";
                        exceeded++;
                    } else if (ratio >= 0.8d) {
                        status = "near";
                        near++;
                    } else {
                        continue;
                    }
                    all.add(new QuotaAlertRow(
                            rs.getString("api_key"),
                            rs.getString("name"),
                            limit,
                            used,
                            ratio,
                            status));
                }
                Collections.sort(all, new java.util.Comparator<QuotaAlertRow>() {
                    @Override
                    public int compare(QuotaAlertRow a, QuotaAlertRow b) {
                        return Double.compare(b.ratio, a.ratio);
                    }
                });
                List<QuotaAlertRow> top = all.size() > 20 ? all.subList(0, 20) : all;
                return new QuotaAlertResult(near, exceeded, new ArrayList<QuotaAlertRow>(top));
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    public long sumTotalTokensForCurrentMonth(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return 0L;
        }
        final String key = apiKey.trim();
        YearMonth ym = YearMonth.now(zoneId);
        final String from = ym.atDay(1).format(DAY);
        final String toExclusive = ym.plusMonths(1).atDay(1).format(DAY);
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<Long>() {
                @Override
                public Long run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT COALESCE(SUM(total_tokens), 0) FROM usage_daily "
                                    + "WHERE api_key = ? AND usage_date >= ? AND usage_date < ?");
                    try {
                        ps.setString(1, key);
                        ps.setString(2, from);
                        ps.setString(3, toExclusive);
                        ResultSet rs = ps.executeQuery();
                        try {
                            if (rs.next()) {
                                return Long.valueOf(rs.getLong(1));
                            }
                            return Long.valueOf(0L);
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            }).longValue();
        } catch (SQLException e) {
            log.warn("sumTotalTokensForCurrentMonth failed: {}", e.getMessage());
            return 0L;
        }
    }

    public String queryByKeyJson(String apiKey, String from, String to) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "{\"models\":[]}";
        }
        final String key = apiKey.trim();
        try {
            List<Row> rows = db.withConnection(new SqliteDatabase.SqlWork<List<Row>>() {
                @Override
                public List<Row> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT usage_date, model, request_count, prompt_tokens, "
                                    + "completion_tokens, total_tokens FROM usage_daily "
                                    + "WHERE api_key = ?");
                    List<String> params = new ArrayList<String>();
                    params.add(key);
                    appendDateFilters(sql, params, from, to);
                    sql.append(" ORDER BY model ASC, usage_date ASC");
                    return queryRows(connection, sql.toString(), params);
                }
            });
            return toModelsJson(rows);
        } catch (SQLException e) {
            log.warn("queryByKey failed: {}", e.getMessage());
            return "{\"models\":[],\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    public String queryByGroupJson(String groupName, String from, String to) {
        try {
            final String group = groupName == null ? "" : groupName.trim();
            List<Row> rows = db.withConnection(new SqliteDatabase.SqlWork<List<Row>>() {
                @Override
                public List<Row> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT u.usage_date AS usage_date, u.model AS model, "
                                    + "SUM(u.request_count) AS request_count, "
                                    + "SUM(u.prompt_tokens) AS prompt_tokens, "
                                    + "SUM(u.completion_tokens) AS completion_tokens, "
                                    + "SUM(u.total_tokens) AS total_tokens "
                                    + "FROM usage_daily u "
                                    + "LEFT JOIN api_keys k ON u.api_key = k.api_key "
                                    + "WHERE 1=1");
                    List<String> params = new ArrayList<String>();
                    if (!group.isEmpty()) {
                        sql.append(" AND COALESCE(k.group_name, 'default') = ?");
                        params.add(group);
                    }
                    if (from != null && !from.isEmpty()) {
                        sql.append(" AND u.usage_date >= ?");
                        params.add(from);
                    }
                    if (to != null && !to.isEmpty()) {
                        sql.append(" AND u.usage_date <= ?");
                        params.add(to);
                    }
                    sql.append(" GROUP BY u.usage_date, u.model ORDER BY u.model ASC, u.usage_date ASC");
                    return queryRows(connection, sql.toString(), params);
                }
            });
            return toModelsJson(rows);
        } catch (SQLException e) {
            log.warn("queryByGroup failed: {}", e.getMessage());
            return "{\"models\":[],\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    /**
     * Top N by total_tokens for names (api keys) and groups within an optional date range.
     * {@code limit <= 0} means no LIMIT (return all ranked rows).
     */
    public String queryRankJson(String from, String to, int limit) {
        final int top = limit;
        try {
            List<NameRank> names = db.withConnection(new SqliteDatabase.SqlWork<List<NameRank>>() {
                @Override
                public List<NameRank> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT u.api_key AS api_key, "
                                    + "COALESCE(NULLIF(k.name, ''), u.api_key) AS name, "
                                    + "COALESCE(k.group_name, 'default') AS group_name, "
                                    + "SUM(u.request_count) AS request_count, "
                                    + "SUM(u.prompt_tokens) AS prompt_tokens, "
                                    + "SUM(u.completion_tokens) AS completion_tokens, "
                                    + "SUM(u.total_tokens) AS total_tokens "
                                    + "FROM usage_daily u "
                                    + "LEFT JOIN api_keys k ON u.api_key = k.api_key "
                                    + "WHERE 1=1");
                    List<String> params = new ArrayList<String>();
                    appendDateFiltersPrefixed(sql, params, "u.", from, to);
                    sql.append(" GROUP BY u.api_key ORDER BY total_tokens DESC, request_count DESC");
                    if (top > 0) {
                        sql.append(" LIMIT ?");
                    }
                    PreparedStatement ps = connection.prepareStatement(sql.toString());
                    try {
                        for (int i = 0; i < params.size(); i++) {
                            ps.setString(i + 1, params.get(i));
                        }
                        if (top > 0) {
                            ps.setInt(params.size() + 1, top);
                        }
                        ResultSet rs = ps.executeQuery();
                        try {
                            List<NameRank> list = new ArrayList<NameRank>();
                            int rank = 1;
                            while (rs.next()) {
                                list.add(new NameRank(
                                        rank++,
                                        rs.getString("api_key"),
                                        rs.getString("name"),
                                        rs.getString("group_name"),
                                        rs.getLong("request_count"),
                                        rs.getLong("prompt_tokens"),
                                        rs.getLong("completion_tokens"),
                                        rs.getLong("total_tokens")));
                            }
                            return list;
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });

            List<GroupRank> groups = db.withConnection(new SqliteDatabase.SqlWork<List<GroupRank>>() {
                @Override
                public List<GroupRank> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT COALESCE(k.group_name, 'default') AS group_name, "
                                    + "SUM(u.request_count) AS request_count, "
                                    + "SUM(u.prompt_tokens) AS prompt_tokens, "
                                    + "SUM(u.completion_tokens) AS completion_tokens, "
                                    + "SUM(u.total_tokens) AS total_tokens "
                                    + "FROM usage_daily u "
                                    + "LEFT JOIN api_keys k ON u.api_key = k.api_key "
                                    + "WHERE 1=1");
                    List<String> params = new ArrayList<String>();
                    appendDateFiltersPrefixed(sql, params, "u.", from, to);
                    sql.append(" GROUP BY COALESCE(k.group_name, 'default') "
                            + "ORDER BY total_tokens DESC, request_count DESC");
                    if (top > 0) {
                        sql.append(" LIMIT ?");
                    }
                    PreparedStatement ps = connection.prepareStatement(sql.toString());
                    try {
                        for (int i = 0; i < params.size(); i++) {
                            ps.setString(i + 1, params.get(i));
                        }
                        if (top > 0) {
                            ps.setInt(params.size() + 1, top);
                        }
                        ResultSet rs = ps.executeQuery();
                        try {
                            List<GroupRank> list = new ArrayList<GroupRank>();
                            int rank = 1;
                            while (rs.next()) {
                                list.add(new GroupRank(
                                        rank++,
                                        rs.getString("group_name"),
                                        rs.getLong("request_count"),
                                        rs.getLong("prompt_tokens"),
                                        rs.getLong("completion_tokens"),
                                        rs.getLong("total_tokens")));
                            }
                            return list;
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });

            return toRankJson(names, groups);
        } catch (SQLException e) {
            log.warn("queryRank failed: {}", e.getMessage());
            return "{\"by_name\":[],\"by_group\":[],\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    public String queryLatencyByModelJson(String from, String to, String model) {
        try {
            final String modelFilter = model == null ? "" : model.trim();
            List<LatencyRow> rows = db.withConnection(new SqliteDatabase.SqlWork<List<LatencyRow>>() {
                @Override
                public List<LatencyRow> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT model, hour_bucket, request_count, latency_sum_ms, "
                                    + "latency_max_ms, ttft_sum_ms, ttft_count "
                                    + "FROM latency_hourly WHERE 1=1");
                    List<String> params = new ArrayList<String>();
                    appendHourBucketFilters(sql, params, from, to);
                    if (!modelFilter.isEmpty()) {
                        sql.append(" AND model = ?");
                        params.add(modelFilter);
                    }
                    sql.append(" ORDER BY model ASC, hour_bucket ASC");
                    PreparedStatement ps = connection.prepareStatement(sql.toString());
                    try {
                        for (int i = 0; i < params.size(); i++) {
                            ps.setString(i + 1, params.get(i));
                        }
                        ResultSet rs = ps.executeQuery();
                        try {
                            List<LatencyRow> list = new ArrayList<LatencyRow>();
                            while (rs.next()) {
                                list.add(new LatencyRow(
                                        rs.getString("model"),
                                        rs.getString("hour_bucket"),
                                        rs.getLong("request_count"),
                                        rs.getLong("latency_sum_ms"),
                                        rs.getLong("latency_max_ms"),
                                        rs.getLong("ttft_sum_ms"),
                                        rs.getLong("ttft_count")));
                            }
                            return list;
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });
            return toLatencyModelsJson(rows);
        } catch (SQLException e) {
            log.warn("queryLatencyByModel failed: {}", e.getMessage());
            return "{\"models\":[],\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private static void appendHourBucketFilters(StringBuilder sql, List<String> params,
                                                String from, String to) {
        if (from != null && !from.isEmpty()) {
            sql.append(" AND hour_bucket >= ?");
            params.add(from.trim() + " 00");
        }
        if (to != null && !to.isEmpty()) {
            sql.append(" AND hour_bucket <= ?");
            params.add(to.trim() + " 23");
        }
    }

    private static String toLatencyModelsJson(List<LatencyRow> rows) {
        Map<String, LatencyModelAgg> byModel = new LinkedHashMap<String, LatencyModelAgg>();
        for (LatencyRow row : rows) {
            String model = row.model == null ? "unknown" : row.model;
            LatencyModelAgg agg = byModel.get(model);
            if (agg == null) {
                agg = new LatencyModelAgg(model);
                byModel.put(model, agg);
            }
            agg.requestTotal += row.requestCount;
            agg.series.add(row);
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"models\":[");
        int i = 0;
        for (LatencyModelAgg agg : byModel.values()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append("{\"model\":\"").append(escape(agg.model)).append("\"")
                    .append(",\"request_total\":").append(agg.requestTotal)
                    .append(",\"series\":[");
            for (int j = 0; j < agg.series.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                LatencyRow r = agg.series.get(j);
                long avgLatency = r.requestCount > 0 ? r.latencySumMs / r.requestCount : 0L;
                long avgTtft = r.ttftCount > 0 ? r.ttftSumMs / r.ttftCount : 0L;
                sb.append("{\"bucket\":\"").append(escape(r.hourBucket)).append("\"")
                        .append(",\"request_count\":").append(r.requestCount)
                        .append(",\"avg_ttft_ms\":").append(avgTtft)
                        .append(",\"avg_latency_ms\":").append(avgLatency)
                        .append(",\"latency_sum_ms\":").append(r.latencySumMs)
                        .append(",\"latency_max_ms\":").append(r.latencyMaxMs)
                        .append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendDateFilters(StringBuilder sql, List<String> params,
                                          String from, String to) {
        appendDateFiltersPrefixed(sql, params, "", from, to);
    }

    private static void appendDateFiltersPrefixed(StringBuilder sql, List<String> params,
                                                  String columnPrefix, String from, String to) {
        String col = (columnPrefix == null ? "" : columnPrefix) + "usage_date";
        if (from != null && !from.isEmpty()) {
            sql.append(" AND ").append(col).append(" >= ?");
            params.add(from);
        }
        if (to != null && !to.isEmpty()) {
            sql.append(" AND ").append(col).append(" <= ?");
            params.add(to);
        }
    }

    private static String toRankJson(List<NameRank> names, List<GroupRank> groups) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"by_name\":[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            NameRank n = names.get(i);
            sb.append("{\"rank\":").append(n.rank)
                    .append(",\"api_key\":\"").append(escape(n.apiKey)).append("\"")
                    .append(",\"name\":\"").append(escape(n.name)).append("\"")
                    .append(",\"group_name\":\"").append(escape(n.groupName)).append("\"")
                    .append(",\"request_count\":").append(n.requestCount)
                    .append(",\"prompt_tokens\":").append(n.promptTokens)
                    .append(",\"completion_tokens\":").append(n.completionTokens)
                    .append(",\"total_tokens\":").append(n.totalTokens)
                    .append('}');
        }
        sb.append("],\"by_group\":[");
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            GroupRank g = groups.get(i);
            sb.append("{\"rank\":").append(g.rank)
                    .append(",\"group_name\":\"").append(escape(g.groupName)).append("\"")
                    .append(",\"request_count\":").append(g.requestCount)
                    .append(",\"prompt_tokens\":").append(g.promptTokens)
                    .append(",\"completion_tokens\":").append(g.completionTokens)
                    .append(",\"total_tokens\":").append(g.totalTokens)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static List<Row> queryRows(java.sql.Connection connection, String sql,
                                       List<String> params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            ResultSet rs = ps.executeQuery();
            try {
                List<Row> list = new ArrayList<Row>();
                while (rs.next()) {
                    list.add(new Row(
                            rs.getString("usage_date"),
                            rs.getString("model"),
                            rs.getLong("request_count"),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("total_tokens")));
                }
                return list;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static String toModelsJson(List<Row> rows) {
        Map<String, ModelAgg> byModel = new LinkedHashMap<String, ModelAgg>();
        for (Row row : rows) {
            String model = row.model == null ? "unknown" : row.model;
            ModelAgg agg = byModel.get(model);
            if (agg == null) {
                agg = new ModelAgg(model);
                byModel.put(model, agg);
            }
            agg.requestTotal += row.requests;
            agg.tokenTotal += row.totalTokens;
            agg.series.add(row);
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"models\":[");
        int i = 0;
        for (ModelAgg agg : byModel.values()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append("{\"model\":\"").append(escape(agg.model)).append("\"")
                    .append(",\"request_total\":").append(agg.requestTotal)
                    .append(",\"token_total\":").append(agg.tokenTotal)
                    .append(",\"series\":[");
            for (int j = 0; j < agg.series.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                Row r = agg.series.get(j);
                sb.append("{\"date\":\"").append(escape(r.date)).append("\"")
                        .append(",\"requests\":").append(r.requests)
                        .append(",\"prompt_tokens\":").append(r.promptTokens)
                        .append(",\"completion_tokens\":").append(r.completionTokens)
                        .append(",\"total_tokens\":").append(r.totalTokens)
                        .append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Row {
        final String date;
        final String model;
        final long requests;
        final long promptTokens;
        final long completionTokens;
        final long totalTokens;

        Row(String date, String model, long requests, long promptTokens,
            long completionTokens, long totalTokens) {
            this.date = date;
            this.model = model;
            this.requests = requests;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    private static final class ModelAgg {
        final String model;
        long requestTotal;
        long tokenTotal;
        final List<Row> series = new ArrayList<Row>();

        ModelAgg(String model) {
            this.model = model;
        }
    }

    private static final class NameRank {
        final int rank;
        final String apiKey;
        final String name;
        final String groupName;
        final long requestCount;
        final long promptTokens;
        final long completionTokens;
        final long totalTokens;

        NameRank(int rank, String apiKey, String name, String groupName,
                 long requestCount, long promptTokens, long completionTokens, long totalTokens) {
            this.rank = rank;
            this.apiKey = apiKey;
            this.name = name;
            this.groupName = groupName;
            this.requestCount = requestCount;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    private static final class GroupRank {
        final int rank;
        final String groupName;
        final long requestCount;
        final long promptTokens;
        final long completionTokens;
        final long totalTokens;

        GroupRank(int rank, String groupName, long requestCount, long promptTokens,
                  long completionTokens, long totalTokens) {
            this.rank = rank;
            this.groupName = groupName;
            this.requestCount = requestCount;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    private static final class LatencyRow {
        final String model;
        final String hourBucket;
        final long requestCount;
        final long latencySumMs;
        final long latencyMaxMs;
        final long ttftSumMs;
        final long ttftCount;

        LatencyRow(String model, String hourBucket, long requestCount,
                   long latencySumMs, long latencyMaxMs, long ttftSumMs, long ttftCount) {
            this.model = model;
            this.hourBucket = hourBucket;
            this.requestCount = requestCount;
            this.latencySumMs = latencySumMs;
            this.latencyMaxMs = latencyMaxMs;
            this.ttftSumMs = ttftSumMs;
            this.ttftCount = ttftCount;
        }
    }

    private static final class LatencyModelAgg {
        final String model;
        long requestTotal;
        final List<LatencyRow> series = new ArrayList<LatencyRow>();

        LatencyModelAgg(String model) {
            this.model = model;
        }
    }

    private static final class TrendPoint {
        final String date;
        final long totalTokens;
        final long requestCount;

        TrendPoint(String date, long totalTokens, long requestCount) {
            this.date = date;
            this.totalTokens = totalTokens;
            this.requestCount = requestCount;
        }
    }

    private static final class LatencyPoint {
        final String bucket;
        final long avgLatencyMs;
        final long requestCount;

        LatencyPoint(String bucket, long avgLatencyMs, long requestCount) {
            this.bucket = bucket;
            this.avgLatencyMs = avgLatencyMs;
            this.requestCount = requestCount;
        }
    }

    private static final class TopUser {
        final String name;
        final String groupName;
        final long totalTokens;
        final long requestCount;

        TopUser(String name, String groupName, long totalTokens, long requestCount) {
            this.name = name;
            this.groupName = groupName;
            this.totalTokens = totalTokens;
            this.requestCount = requestCount;
        }
    }

    private static final class QuotaAlertRow {
        final String apiKey;
        final String name;
        final long limit;
        final long used;
        final double ratio;
        final String status;

        QuotaAlertRow(String apiKey, String name, long limit, long used, double ratio, String status) {
            this.apiKey = apiKey;
            this.name = name;
            this.limit = limit;
            this.used = used;
            this.ratio = ratio;
            this.status = status;
        }
    }

    private static final class QuotaAlertResult {
        final int nearCount;
        final int exceededCount;
        final List<QuotaAlertRow> rows;

        QuotaAlertResult(int nearCount, int exceededCount, List<QuotaAlertRow> rows) {
            this.nearCount = nearCount;
            this.exceededCount = exceededCount;
            this.rows = rows;
        }
    }
}
