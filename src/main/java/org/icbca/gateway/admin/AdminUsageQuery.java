package org.icbca.gateway.admin;

import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds chart-oriented usage series from {@code usage_daily}.
 */
public final class AdminUsageQuery {

    private static final Logger log = LoggerFactory.getLogger(AdminUsageQuery.class);

    private final SqliteDatabase db;

    public AdminUsageQuery(SqliteDatabase db) {
        this.db = db;
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
}
