package org.icbca.gateway.admin;

import org.icbca.gateway.db.SqliteDatabase;
import org.icbca.gateway.usage.LatencyHistBins;
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
                    long[] monthTotals = queryMonthTotals(connection, monthFrom, monthToExcl);
                    long activeUsers = queryActiveUsers(connection, todayStr);
                    List<TrendPoint> trend = queryTokenTrend(connection, trendFrom, todayStr);
                    List<LatencyPoint> latency = queryLatency24h(connection, latencyFrom);
                    Map<String, long[]> histByBucketMetric =
                            queryHistAggByBucket(connection, latencyFrom, null, null);
                    BenchmarkAgg bench24h = aggregateBenchmark(latency, histByBucketMetric);
                    List<TopUser> topUsers = queryTopUsers(connection, todayStr);
                    List<TopDepartment> topDepartments = queryTopDepartments(connection, todayStr);

                    String generatedAt = java.time.ZonedDateTime.now(zoneId).format(ISO_INSTANT);
                    StringBuilder sb = new StringBuilder(1024);
                    sb.append("{\"generated_at\":\"").append(escape(generatedAt)).append("\"")
                            .append(",\"kpis\":{")
                            .append("\"today_requests\":").append(todayTotals[0])
                            .append(",\"today_tokens\":").append(todayTotals[1])
                            .append(",\"month_tokens\":").append(monthTotals[0])
                            .append(",\"today_active_users\":").append(activeUsers)
                            .append(",\"month_requests\":").append(monthTotals[1])
                            .append(",\"avg_ttft_ms\":").append(bench24h.avgTtftMs)
                            .append(",\"p50_ttft_ms\":").append(bench24h.p50TtftMs)
                            .append(",\"p99_ttft_ms\":").append(bench24h.p99TtftMs)
                            .append(",\"avg_tpot_ms\":").append(bench24h.avgTpotMs)
                            .append(",\"p50_tpot_ms\":").append(bench24h.p50TpotMs)
                            .append(",\"p99_tpot_ms\":").append(bench24h.p99TpotMs)
                            .append(",\"avg_itl_ms\":").append(bench24h.avgItlMs)
                            .append(",\"p50_itl_ms\":").append(bench24h.p50ItlMs)
                            .append(",\"p99_itl_ms\":").append(bench24h.p99ItlMs)
                            .append(",\"request_tps\":").append(formatDouble(bench24h.requestTps))
                            .append(",\"output_tps\":").append(formatDouble(bench24h.outputTps))
                            .append(",\"total_token_tps\":").append(formatDouble(bench24h.totalTokenTps))
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
                        appendLatencyPointJson(sb, p);
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
                                .append(",\"department\":\"").append(escape(u.department)).append("\"")
                                .append(",\"total_tokens\":").append(u.totalTokens)
                                .append(",\"request_count\":").append(u.requestCount)
                                .append('}');
                    }
                    sb.append(']');

                    sb.append(",\"top_departments_today\":[");
                    for (int i = 0; i < topDepartments.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        TopDepartment d = topDepartments.get(i);
                        sb.append("{\"department\":\"").append(escape(d.department)).append("\"")
                                .append(",\"total_tokens\":").append(d.totalTokens)
                                .append(",\"request_count\":").append(d.requestCount)
                                .append('}');
                    }
                    sb.append(']');

                    sb.append(",\"token_breakdown_today\":{")
                            .append("\"prompt_tokens\":").append(todayTotals[2])
                            .append(",\"completion_tokens\":").append(todayTotals[3])
                            .append("}}");
                    return sb.toString();
                }
            });
        } catch (SQLException e) {
            log.warn("queryOverview failed: {}", e.getMessage());
            return "{\"generated_at\":\"\",\"kpis\":{\"today_requests\":0,\"today_tokens\":0,"
                    + "\"month_tokens\":0,\"today_active_users\":0,\"month_requests\":0,"
                    + "\"avg_ttft_ms\":0,\"p50_ttft_ms\":0,\"p99_ttft_ms\":0,"
                    + "\"avg_tpot_ms\":0,\"p50_tpot_ms\":0,\"p99_tpot_ms\":0,"
                    + "\"avg_itl_ms\":0,\"p50_itl_ms\":0,\"p99_itl_ms\":0,"
                    + "\"request_tps\":0,\"output_tps\":0,\"total_token_tps\":0},"
                    + "\"token_trend_7d\":[],\"latency_24h\":[],\"top_users_today\":[],"
                    + "\"top_departments_today\":[],"
                    + "\"token_breakdown_today\":{\"prompt_tokens\":0,\"completion_tokens\":0},"
                    + "\"error\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private static long[] queryTodayTotals(java.sql.Connection connection, String today)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(request_count), 0), COALESCE(SUM(total_tokens), 0), "
                        + "COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0) "
                        + "FROM usage_daily WHERE usage_date = ?");
        try {
            ps.setString(1, today);
            ResultSet rs = ps.executeQuery();
            try {
                if (rs.next()) {
                    return new long[] {
                            rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)
                    };
                }
                return new long[] { 0L, 0L, 0L, 0L };
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /** Returns [month_tokens, month_requests]. */
    private static long[] queryMonthTotals(java.sql.Connection connection, String from, String toExcl)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(total_tokens), 0), COALESCE(SUM(request_count), 0) "
                        + "FROM usage_daily WHERE usage_date >= ? AND usage_date < ?");
        try {
            ps.setString(1, from);
            ps.setString(2, toExcl);
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
        Map<String, long[]> hist = queryHistAggByBucket(connection, fromBucket, null, null);
        PreparedStatement ps = connection.prepareStatement(
                "SELECT hour_bucket, "
                        + "COALESCE(SUM(latency_sum_ms), 0) AS latency_sum_ms, "
                        + "COALESCE(SUM(request_count), 0) AS request_count, "
                        + "COALESCE(SUM(ttft_sum_ms), 0) AS ttft_sum_ms, "
                        + "COALESCE(SUM(ttft_count), 0) AS ttft_count, "
                        + "COALESCE(SUM(tpot_sum_ms), 0) AS tpot_sum_ms, "
                        + "COALESCE(SUM(tpot_count), 0) AS tpot_count, "
                        + "COALESCE(SUM(itl_sum_ms), 0) AS itl_sum_ms, "
                        + "COALESCE(SUM(itl_count), 0) AS itl_count, "
                        + "COALESCE(SUM(output_tps_milli_sum), 0) AS output_tps_milli_sum, "
                        + "COALESCE(SUM(output_tps_count), 0) AS output_tps_count, "
                        + "COALESCE(SUM(prompt_tokens_sum), 0) AS prompt_tokens_sum, "
                        + "COALESCE(SUM(completion_tokens_sum), 0) AS completion_tokens_sum "
                        + "FROM latency_hourly WHERE hour_bucket >= ? "
                        + "GROUP BY hour_bucket ORDER BY hour_bucket ASC");
        try {
            ps.setString(1, fromBucket);
            ResultSet rs = ps.executeQuery();
            try {
                List<LatencyPoint> list = new ArrayList<LatencyPoint>();
                while (rs.next()) {
                    String bucket = rs.getString("hour_bucket");
                    list.add(buildLatencyPoint(
                            bucket,
                            rs.getLong("request_count"),
                            rs.getLong("latency_sum_ms"),
                            rs.getLong("ttft_sum_ms"),
                            rs.getLong("ttft_count"),
                            rs.getLong("tpot_sum_ms"),
                            rs.getLong("tpot_count"),
                            rs.getLong("itl_sum_ms"),
                            rs.getLong("itl_count"),
                            rs.getLong("output_tps_milli_sum"),
                            rs.getLong("output_tps_count"),
                            rs.getLong("prompt_tokens_sum"),
                            rs.getLong("completion_tokens_sum"),
                            hist));
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
                        + "COALESCE(MAX(k.department), 'FTD') AS department, "
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
                            rs.getString("department"),
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

    private static List<TopDepartment> queryTopDepartments(java.sql.Connection connection,
                                                           String today)
            throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(k.department, 'FTD') AS department, "
                        + "COALESCE(SUM(u.total_tokens), 0) AS total_tokens, "
                        + "COALESCE(SUM(u.request_count), 0) AS request_count "
                        + "FROM usage_daily u LEFT JOIN api_keys k ON u.api_key = k.api_key "
                        + "WHERE u.usage_date = ? "
                        + "GROUP BY COALESCE(k.department, 'FTD') "
                        + "ORDER BY total_tokens DESC, request_count DESC LIMIT 10");
        try {
            ps.setString(1, today);
            ResultSet rs = ps.executeQuery();
            try {
                List<TopDepartment> list = new ArrayList<TopDepartment>();
                while (rs.next()) {
                    list.add(new TopDepartment(
                            rs.getString("department"),
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

    public String queryByCategoryJson(String from, String to, String apiKeyFilter) {
        try {
            final String keyFilter = apiKeyFilter == null ? "" : apiKeyFilter.trim();
            return db.withConnection(new SqliteDatabase.SqlWork<String>() {
                @Override
                public String run(java.sql.Connection connection) throws SQLException {
                    List<String> params = new ArrayList<String>();
                    StringBuilder where = new StringBuilder(" WHERE 1=1");
                    if (!keyFilter.isEmpty()) {
                        where.append(" AND api_key = ?");
                        params.add(keyFilter);
                    }
                    if (from != null && !from.isEmpty()) {
                        where.append(" AND usage_date >= ?");
                        params.add(from);
                    }
                    if (to != null && !to.isEmpty()) {
                        where.append(" AND usage_date <= ?");
                        params.add(to);
                    }

                    PreparedStatement catPs = connection.prepareStatement(
                            "SELECT category, COALESCE(SUM(request_count), 0) AS request_count "
                                    + "FROM category_daily"
                                    + where
                                    + " GROUP BY category ORDER BY request_count DESC, category ASC");
                    List<CatAgg> byCategory = new ArrayList<CatAgg>();
                    try {
                        for (int i = 0; i < params.size(); i++) {
                            catPs.setString(i + 1, params.get(i));
                        }
                        ResultSet rs = catPs.executeQuery();
                        try {
                            while (rs.next()) {
                                byCategory.add(new CatAgg(
                                        rs.getString("category"), rs.getLong("request_count")));
                            }
                        } finally {
                            rs.close();
                        }
                    } finally {
                        catPs.close();
                    }

                    PreparedStatement userPs = connection.prepareStatement(
                            "SELECT api_key, MAX(name) AS name, category, "
                                    + "COALESCE(SUM(request_count), 0) AS request_count "
                                    + "FROM category_daily"
                                    + where
                                    + " GROUP BY api_key, category "
                                    + "ORDER BY api_key ASC, request_count DESC, category ASC");
                    Map<String, UserCatAgg> byUser = new LinkedHashMap<String, UserCatAgg>();
                    try {
                        for (int i = 0; i < params.size(); i++) {
                            userPs.setString(i + 1, params.get(i));
                        }
                        ResultSet rs = userPs.executeQuery();
                        try {
                            while (rs.next()) {
                                String apiKey = rs.getString("api_key");
                                UserCatAgg user = byUser.get(apiKey);
                                if (user == null) {
                                    user = new UserCatAgg(apiKey, rs.getString("name"));
                                    byUser.put(apiKey, user);
                                }
                                long cnt = rs.getLong("request_count");
                                user.requestCount += cnt;
                                user.categories.add(new CatAgg(rs.getString("category"), cnt));
                            }
                        } finally {
                            rs.close();
                        }
                    } finally {
                        userPs.close();
                    }

                    StringBuilder sb = new StringBuilder(512);
                    sb.append("{\"by_category\":[");
                    for (int i = 0; i < byCategory.size(); i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        CatAgg c = byCategory.get(i);
                        sb.append("{\"category\":\"").append(escape(c.category)).append("\"")
                                .append(",\"request_count\":").append(c.requestCount)
                                .append('}');
                    }
                    sb.append("],\"by_user\":[");
                    int ui = 0;
                    for (UserCatAgg u : byUser.values()) {
                        if (ui++ > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"api_key\":\"").append(escape(u.apiKey)).append("\"")
                                .append(",\"name\":\"").append(escape(u.name)).append("\"")
                                .append(",\"request_count\":").append(u.requestCount)
                                .append(",\"categories\":[");
                        for (int j = 0; j < u.categories.size(); j++) {
                            if (j > 0) {
                                sb.append(',');
                            }
                            CatAgg c = u.categories.get(j);
                            sb.append("{\"category\":\"").append(escape(c.category)).append("\"")
                                    .append(",\"request_count\":").append(c.requestCount)
                                    .append('}');
                        }
                        sb.append("]}");
                    }
                    sb.append("]}");
                    return sb.toString();
                }
            });
        } catch (SQLException e) {
            log.warn("queryByCategory failed: {}", e.getMessage());
            return "{\"by_category\":[],\"by_user\":[],\"error\":\""
                    + escape(e.getMessage()) + "\"}";
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

    public String queryByDepartmentJson(String department, String from, String to) {
        try {
            final String dept = department == null ? "" : department.trim();
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
                    if (!dept.isEmpty()) {
                        sql.append(" AND COALESCE(k.department, 'FTD') = ?");
                        params.add(dept);
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
            log.warn("queryByDepartment failed: {}", e.getMessage());
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
                                    + "COALESCE(k.department, 'FTD') AS department, "
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
                                        rs.getString("department"),
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

            List<DepartmentRank> departments = db.withConnection(
                    new SqliteDatabase.SqlWork<List<DepartmentRank>>() {
                @Override
                public List<DepartmentRank> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT COALESCE(k.department, 'FTD') AS department, "
                                    + "SUM(u.request_count) AS request_count, "
                                    + "SUM(u.prompt_tokens) AS prompt_tokens, "
                                    + "SUM(u.completion_tokens) AS completion_tokens, "
                                    + "SUM(u.total_tokens) AS total_tokens "
                                    + "FROM usage_daily u "
                                    + "LEFT JOIN api_keys k ON u.api_key = k.api_key "
                                    + "WHERE 1=1");
                    List<String> params = new ArrayList<String>();
                    appendDateFiltersPrefixed(sql, params, "u.", from, to);
                    sql.append(" GROUP BY COALESCE(k.department, 'FTD') "
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
                            List<DepartmentRank> list = new ArrayList<DepartmentRank>();
                            int rank = 1;
                            while (rs.next()) {
                                list.add(new DepartmentRank(
                                        rank++,
                                        rs.getString("department"),
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

            return toRankJson(names, groups, departments);
        } catch (SQLException e) {
            log.warn("queryRank failed: {}", e.getMessage());
            return "{\"by_name\":[],\"by_group\":[],\"by_department\":[],\"error\":\""
                    + escape(e.getMessage()) + "\"}";
        }
    }

    public String queryLatencyByModelJson(String from, String to, String model) {
        try {
            final String modelFilter = model == null ? "" : model.trim();
            return db.withConnection(new SqliteDatabase.SqlWork<String>() {
                @Override
                public String run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT model, hour_bucket, request_count, latency_sum_ms, "
                                    + "latency_max_ms, ttft_sum_ms, ttft_count, "
                                    + "tpot_sum_ms, tpot_count, itl_sum_ms, itl_count, "
                                    + "output_tps_milli_sum, output_tps_count, "
                                    + "prompt_tokens_sum, completion_tokens_sum "
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
                        List<LatencyRow> list = new ArrayList<LatencyRow>();
                        ResultSet rs = ps.executeQuery();
                        try {
                            while (rs.next()) {
                                list.add(new LatencyRow(
                                        rs.getString("model"),
                                        rs.getString("hour_bucket"),
                                        rs.getLong("request_count"),
                                        rs.getLong("latency_sum_ms"),
                                        rs.getLong("latency_max_ms"),
                                        rs.getLong("ttft_sum_ms"),
                                        rs.getLong("ttft_count"),
                                        rs.getLong("tpot_sum_ms"),
                                        rs.getLong("tpot_count"),
                                        rs.getLong("itl_sum_ms"),
                                        rs.getLong("itl_count"),
                                        rs.getLong("output_tps_milli_sum"),
                                        rs.getLong("output_tps_count"),
                                        rs.getLong("prompt_tokens_sum"),
                                        rs.getLong("completion_tokens_sum")));
                            }
                        } finally {
                            rs.close();
                        }
                        Map<String, long[]> hist = queryHistByModelBucket(
                                connection, from, to, modelFilter);
                        return toLatencyModelsJson(list, hist);
                    } finally {
                        ps.close();
                    }
                }
            });
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

    private static String toLatencyModelsJson(List<LatencyRow> rows, Map<String, long[]> hist) {
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
                LatencyPoint p = metricsFromRow(r, hist);
                appendLatencyPointJson(sb, p);
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static LatencyPoint metricsFromRow(LatencyRow r, Map<String, long[]> hist) {
        String model = r.model == null ? "unknown" : r.model;
        long avgLatency = r.requestCount > 0 ? r.latencySumMs / r.requestCount : 0L;
        long avgTtft = r.ttftCount > 0 ? r.ttftSumMs / r.ttftCount : 0L;
        long avgTpot = r.tpotCount > 0 ? r.tpotSumMs / r.tpotCount : 0L;
        long avgItl = r.itlCount > 0 ? r.itlSumMs / r.itlCount : 0L;
        double outputTps = r.outputTpsCount > 0
                ? (r.outputTpsMilliSum / (double) r.outputTpsCount) / 1000.0 : 0.0;
        double requestTps = r.requestCount / 3600.0;
        double totalTokenTps = (r.promptTokensSum + r.completionTokensSum) / 3600.0;
        long p50Ttft = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_TTFT, 0.50);
        long p99Ttft = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_TTFT, 0.99);
        long p50Tpot = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_TPOT, 0.50);
        long p99Tpot = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_TPOT, 0.99);
        long p50Itl = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_ITL, 0.50);
        long p99Itl = percentileFromHist(hist, model, r.hourBucket, LatencyHistBins.METRIC_ITL, 0.99);
        return new LatencyPoint(
                r.hourBucket, avgLatency, r.requestCount,
                avgTtft, p50Ttft, p99Ttft,
                avgTpot, p50Tpot, p99Tpot,
                avgItl, p50Itl, p99Itl,
                requestTps, outputTps, totalTokenTps,
                r.latencySumMs, r.latencyMaxMs,
                r.ttftSumMs, r.ttftCount, r.tpotSumMs, r.tpotCount,
                r.itlSumMs, r.itlCount, r.outputTpsMilliSum, r.outputTpsCount,
                r.promptTokensSum, r.completionTokensSum);
    }

    private static LatencyPoint buildLatencyPoint(
            String bucket, long requestCount, long latencySumMs,
            long ttftSumMs, long ttftCount, long tpotSumMs, long tpotCount,
            long itlSumMs, long itlCount, long outputTpsMilliSum, long outputTpsCount,
            long promptTokensSum, long completionTokensSum,
            Map<String, long[]> histByBucketMetric) {
        long avgLatency = requestCount > 0 ? latencySumMs / requestCount : 0L;
        long avgTtft = ttftCount > 0 ? ttftSumMs / ttftCount : 0L;
        long avgTpot = tpotCount > 0 ? tpotSumMs / tpotCount : 0L;
        long avgItl = itlCount > 0 ? itlSumMs / itlCount : 0L;
        double outputTps = outputTpsCount > 0
                ? (outputTpsMilliSum / (double) outputTpsCount) / 1000.0 : 0.0;
        double requestTps = requestCount / 3600.0;
        double totalTokenTps = (promptTokensSum + completionTokensSum) / 3600.0;
        long p50Ttft = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_TTFT, 0.50);
        long p99Ttft = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_TTFT, 0.99);
        long p50Tpot = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_TPOT, 0.50);
        long p99Tpot = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_TPOT, 0.99);
        long p50Itl = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_ITL, 0.50);
        long p99Itl = percentileFromBucketHist(histByBucketMetric, bucket, LatencyHistBins.METRIC_ITL, 0.99);
        return new LatencyPoint(
                bucket, avgLatency, requestCount,
                avgTtft, p50Ttft, p99Ttft,
                avgTpot, p50Tpot, p99Tpot,
                avgItl, p50Itl, p99Itl,
                requestTps, outputTps, totalTokenTps,
                latencySumMs, 0L,
                ttftSumMs, ttftCount, tpotSumMs, tpotCount,
                itlSumMs, itlCount, outputTpsMilliSum, outputTpsCount,
                promptTokensSum, completionTokensSum);
    }

    private static void appendLatencyPointJson(StringBuilder sb, LatencyPoint p) {
        sb.append("{\"bucket\":\"").append(escape(p.bucket)).append("\"")
                .append(",\"request_count\":").append(p.requestCount)
                .append(",\"avg_latency_ms\":").append(p.avgLatencyMs)
                .append(",\"latency_sum_ms\":").append(p.latencySumMs)
                .append(",\"latency_max_ms\":").append(p.latencyMaxMs)
                .append(",\"avg_ttft_ms\":").append(p.avgTtftMs)
                .append(",\"p50_ttft_ms\":").append(p.p50TtftMs)
                .append(",\"p99_ttft_ms\":").append(p.p99TtftMs)
                .append(",\"avg_tpot_ms\":").append(p.avgTpotMs)
                .append(",\"p50_tpot_ms\":").append(p.p50TpotMs)
                .append(",\"p99_tpot_ms\":").append(p.p99TpotMs)
                .append(",\"avg_itl_ms\":").append(p.avgItlMs)
                .append(",\"p50_itl_ms\":").append(p.p50ItlMs)
                .append(",\"p99_itl_ms\":").append(p.p99ItlMs)
                .append(",\"request_tps\":").append(formatDouble(p.requestTps))
                .append(",\"output_tps\":").append(formatDouble(p.outputTps))
                .append(",\"total_token_tps\":").append(formatDouble(p.totalTokenTps))
                .append('}');
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    /**
     * Key: model|hour_bucket|metric -> parallel bin counts aligned with LatencyHistBins.bins().
     */
    private static Map<String, long[]> queryHistByModelBucket(
            java.sql.Connection connection, String from, String to, String modelFilter)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT model, hour_bucket, metric, bin, cnt FROM latency_hist_hourly WHERE 1=1");
        List<String> params = new ArrayList<String>();
        appendHourBucketFilters(sql, params, from, to);
        if (modelFilter != null && !modelFilter.isEmpty()) {
            sql.append(" AND model = ?");
            params.add(modelFilter);
        }
        PreparedStatement ps = connection.prepareStatement(sql.toString());
        try {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            return loadHistMap(ps, true);
        } finally {
            ps.close();
        }
    }

    /**
     * Key: hour_bucket|metric -> counts (models aggregated).
     */
    private static Map<String, long[]> queryHistAggByBucket(
            java.sql.Connection connection, String fromBucket, String fromDay, String toDay)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT hour_bucket, metric, bin, SUM(cnt) AS cnt "
                        + "FROM latency_hist_hourly WHERE 1=1");
        List<String> params = new ArrayList<String>();
        if (fromBucket != null && !fromBucket.isEmpty()) {
            sql.append(" AND hour_bucket >= ?");
            params.add(fromBucket);
        }
        if (fromDay != null && !fromDay.isEmpty()) {
            sql.append(" AND hour_bucket >= ?");
            params.add(fromDay.trim() + " 00");
        }
        if (toDay != null && !toDay.isEmpty()) {
            sql.append(" AND hour_bucket <= ?");
            params.add(toDay.trim() + " 23");
        }
        sql.append(" GROUP BY hour_bucket, metric, bin");
        PreparedStatement ps = connection.prepareStatement(sql.toString());
        try {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            return loadHistMap(ps, false);
        } finally {
            ps.close();
        }
    }

    private static Map<String, long[]> loadHistMap(PreparedStatement ps, boolean includeModel)
            throws SQLException {
        ResultSet rs = ps.executeQuery();
        try {
            long[] template = LatencyHistBins.bins();
            Map<String, long[]> map = new HashMap<String, long[]>();
            while (rs.next()) {
                String metric = rs.getString("metric");
                String bucket = rs.getString("hour_bucket");
                long bin = rs.getLong("bin");
                long cnt = rs.getLong("cnt");
                String key;
                if (includeModel) {
                    key = rs.getString("model") + "|" + bucket + "|" + metric;
                } else {
                    key = bucket + "|" + metric;
                }
                long[] counts = map.get(key);
                if (counts == null) {
                    counts = new long[template.length];
                    map.put(key, counts);
                }
                for (int i = 0; i < template.length; i++) {
                    if (template[i] == bin) {
                        counts[i] += cnt;
                        break;
                    }
                }
            }
            return map;
        } finally {
            rs.close();
        }
    }

    private static long percentileFromHist(Map<String, long[]> hist, String model,
                                           String bucket, String metric, double p) {
        if (hist == null) {
            return 0L;
        }
        long[] counts = hist.get(model + "|" + bucket + "|" + metric);
        if (counts == null) {
            return 0L;
        }
        return LatencyHistBins.percentile(LatencyHistBins.bins(), counts, p);
    }

    private static long percentileFromBucketHist(Map<String, long[]> hist, String bucket,
                                                 String metric, double p) {
        if (hist == null) {
            return 0L;
        }
        long[] counts = hist.get(bucket + "|" + metric);
        if (counts == null) {
            return 0L;
        }
        return LatencyHistBins.percentile(LatencyHistBins.bins(), counts, p);
    }

    private static BenchmarkAgg aggregateBenchmark(List<LatencyPoint> points,
                                                   Map<String, long[]> histByBucketMetric) {
        BenchmarkAgg agg = new BenchmarkAgg();
        if (points == null || points.isEmpty()) {
            return agg;
        }
        long req = 0L;
        long ttftSum = 0L;
        long ttftCount = 0L;
        long tpotSum = 0L;
        long tpotCount = 0L;
        long itlSum = 0L;
        long itlCount = 0L;
        long outputMilliSum = 0L;
        long outputCount = 0L;
        long promptSum = 0L;
        long completionSum = 0L;
        long[] ttftCounts = new long[LatencyHistBins.bins().length];
        long[] tpotCounts = new long[LatencyHistBins.bins().length];
        long[] itlCounts = new long[LatencyHistBins.bins().length];
        for (LatencyPoint p : points) {
            req += p.requestCount;
            ttftSum += p.ttftSumMs;
            ttftCount += p.ttftCount;
            tpotSum += p.tpotSumMs;
            tpotCount += p.tpotCount;
            itlSum += p.itlSumMs;
            itlCount += p.itlCount;
            outputMilliSum += p.outputTpsMilliSum;
            outputCount += p.outputTpsCount;
            promptSum += p.promptTokensSum;
            completionSum += p.completionTokensSum;
            mergeHist(ttftCounts, histByBucketMetric, p.bucket, LatencyHistBins.METRIC_TTFT);
            mergeHist(tpotCounts, histByBucketMetric, p.bucket, LatencyHistBins.METRIC_TPOT);
            mergeHist(itlCounts, histByBucketMetric, p.bucket, LatencyHistBins.METRIC_ITL);
        }
        agg.avgTtftMs = ttftCount > 0 ? ttftSum / ttftCount : 0L;
        agg.avgTpotMs = tpotCount > 0 ? tpotSum / tpotCount : 0L;
        agg.avgItlMs = itlCount > 0 ? itlSum / itlCount : 0L;
        agg.p50TtftMs = LatencyHistBins.percentile(LatencyHistBins.bins(), ttftCounts, 0.50);
        agg.p99TtftMs = LatencyHistBins.percentile(LatencyHistBins.bins(), ttftCounts, 0.99);
        agg.p50TpotMs = LatencyHistBins.percentile(LatencyHistBins.bins(), tpotCounts, 0.50);
        agg.p99TpotMs = LatencyHistBins.percentile(LatencyHistBins.bins(), tpotCounts, 0.99);
        agg.p50ItlMs = LatencyHistBins.percentile(LatencyHistBins.bins(), itlCounts, 0.50);
        agg.p99ItlMs = LatencyHistBins.percentile(LatencyHistBins.bins(), itlCounts, 0.99);
        double hours = Math.max(1.0, points.size());
        agg.requestTps = req / (hours * 3600.0);
        agg.outputTps = outputCount > 0 ? (outputMilliSum / (double) outputCount) / 1000.0 : 0.0;
        agg.totalTokenTps = (promptSum + completionSum) / (hours * 3600.0);
        return agg;
    }

    private static void mergeHist(long[] dest, Map<String, long[]> hist,
                                  String bucket, String metric) {
        if (hist == null) {
            return;
        }
        long[] src = hist.get(bucket + "|" + metric);
        if (src == null) {
            return;
        }
        for (int i = 0; i < dest.length && i < src.length; i++) {
            dest[i] += src[i];
        }
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

    private static String toRankJson(List<NameRank> names, List<GroupRank> groups,
                                     List<DepartmentRank> departments) {
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
                    .append(",\"department\":\"").append(escape(n.department)).append("\"")
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
        sb.append("],\"by_department\":[");
        for (int i = 0; i < departments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            DepartmentRank d = departments.get(i);
            sb.append("{\"rank\":").append(d.rank)
                    .append(",\"department\":\"").append(escape(d.department)).append("\"")
                    .append(",\"request_count\":").append(d.requestCount)
                    .append(",\"prompt_tokens\":").append(d.promptTokens)
                    .append(",\"completion_tokens\":").append(d.completionTokens)
                    .append(",\"total_tokens\":").append(d.totalTokens)
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

    private static final class CatAgg {
        final String category;
        final long requestCount;

        CatAgg(String category, long requestCount) {
            this.category = category;
            this.requestCount = requestCount;
        }
    }

    private static final class UserCatAgg {
        final String apiKey;
        final String name;
        long requestCount;
        final List<CatAgg> categories = new ArrayList<CatAgg>();

        UserCatAgg(String apiKey, String name) {
            this.apiKey = apiKey;
            this.name = name == null ? "" : name;
        }
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
        final String department;
        final long requestCount;
        final long promptTokens;
        final long completionTokens;
        final long totalTokens;

        NameRank(int rank, String apiKey, String name, String groupName, String department,
                 long requestCount, long promptTokens, long completionTokens, long totalTokens) {
            this.rank = rank;
            this.apiKey = apiKey;
            this.name = name;
            this.groupName = groupName;
            this.department = department;
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

    private static final class DepartmentRank {
        final int rank;
        final String department;
        final long requestCount;
        final long promptTokens;
        final long completionTokens;
        final long totalTokens;

        DepartmentRank(int rank, String department, long requestCount, long promptTokens,
                       long completionTokens, long totalTokens) {
            this.rank = rank;
            this.department = department;
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
        final long tpotSumMs;
        final long tpotCount;
        final long itlSumMs;
        final long itlCount;
        final long outputTpsMilliSum;
        final long outputTpsCount;
        final long promptTokensSum;
        final long completionTokensSum;

        LatencyRow(String model, String hourBucket, long requestCount,
                   long latencySumMs, long latencyMaxMs, long ttftSumMs, long ttftCount,
                   long tpotSumMs, long tpotCount, long itlSumMs, long itlCount,
                   long outputTpsMilliSum, long outputTpsCount,
                   long promptTokensSum, long completionTokensSum) {
            this.model = model;
            this.hourBucket = hourBucket;
            this.requestCount = requestCount;
            this.latencySumMs = latencySumMs;
            this.latencyMaxMs = latencyMaxMs;
            this.ttftSumMs = ttftSumMs;
            this.ttftCount = ttftCount;
            this.tpotSumMs = tpotSumMs;
            this.tpotCount = tpotCount;
            this.itlSumMs = itlSumMs;
            this.itlCount = itlCount;
            this.outputTpsMilliSum = outputTpsMilliSum;
            this.outputTpsCount = outputTpsCount;
            this.promptTokensSum = promptTokensSum;
            this.completionTokensSum = completionTokensSum;
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
        final long avgTtftMs;
        final long p50TtftMs;
        final long p99TtftMs;
        final long avgTpotMs;
        final long p50TpotMs;
        final long p99TpotMs;
        final long avgItlMs;
        final long p50ItlMs;
        final long p99ItlMs;
        final double requestTps;
        final double outputTps;
        final double totalTokenTps;
        final long latencySumMs;
        final long latencyMaxMs;
        final long ttftSumMs;
        final long ttftCount;
        final long tpotSumMs;
        final long tpotCount;
        final long itlSumMs;
        final long itlCount;
        final long outputTpsMilliSum;
        final long outputTpsCount;
        final long promptTokensSum;
        final long completionTokensSum;

        LatencyPoint(String bucket, long avgLatencyMs, long requestCount,
                     long avgTtftMs, long p50TtftMs, long p99TtftMs,
                     long avgTpotMs, long p50TpotMs, long p99TpotMs,
                     long avgItlMs, long p50ItlMs, long p99ItlMs,
                     double requestTps, double outputTps, double totalTokenTps,
                     long latencySumMs, long latencyMaxMs,
                     long ttftSumMs, long ttftCount, long tpotSumMs, long tpotCount,
                     long itlSumMs, long itlCount, long outputTpsMilliSum, long outputTpsCount,
                     long promptTokensSum, long completionTokensSum) {
            this.bucket = bucket;
            this.avgLatencyMs = avgLatencyMs;
            this.requestCount = requestCount;
            this.avgTtftMs = avgTtftMs;
            this.p50TtftMs = p50TtftMs;
            this.p99TtftMs = p99TtftMs;
            this.avgTpotMs = avgTpotMs;
            this.p50TpotMs = p50TpotMs;
            this.p99TpotMs = p99TpotMs;
            this.avgItlMs = avgItlMs;
            this.p50ItlMs = p50ItlMs;
            this.p99ItlMs = p99ItlMs;
            this.requestTps = requestTps;
            this.outputTps = outputTps;
            this.totalTokenTps = totalTokenTps;
            this.latencySumMs = latencySumMs;
            this.latencyMaxMs = latencyMaxMs;
            this.ttftSumMs = ttftSumMs;
            this.ttftCount = ttftCount;
            this.tpotSumMs = tpotSumMs;
            this.tpotCount = tpotCount;
            this.itlSumMs = itlSumMs;
            this.itlCount = itlCount;
            this.outputTpsMilliSum = outputTpsMilliSum;
            this.outputTpsCount = outputTpsCount;
            this.promptTokensSum = promptTokensSum;
            this.completionTokensSum = completionTokensSum;
        }
    }

    private static final class BenchmarkAgg {
        long avgTtftMs;
        long p50TtftMs;
        long p99TtftMs;
        long avgTpotMs;
        long p50TpotMs;
        long p99TpotMs;
        long avgItlMs;
        long p50ItlMs;
        long p99ItlMs;
        double requestTps;
        double outputTps;
        double totalTokenTps;
    }

    private static final class TopUser {
        final String name;
        final String groupName;
        final String department;
        final long totalTokens;
        final long requestCount;

        TopUser(String name, String groupName, String department,
                long totalTokens, long requestCount) {
            this.name = name;
            this.groupName = groupName;
            this.department = department;
            this.totalTokens = totalTokens;
            this.requestCount = requestCount;
        }
    }

    private static final class TopDepartment {
        final String department;
        final long totalTokens;
        final long requestCount;

        TopDepartment(String department, long totalTokens, long requestCount) {
            this.department = department;
            this.totalTokens = totalTokens;
            this.requestCount = requestCount;
        }
    }
}
