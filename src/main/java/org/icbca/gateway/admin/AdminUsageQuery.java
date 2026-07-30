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

    private static void appendDateFilters(StringBuilder sql, List<String> params,
                                          String from, String to) {
        if (from != null && !from.isEmpty()) {
            sql.append(" AND usage_date >= ?");
            params.add(from);
        }
        if (to != null && !to.isEmpty()) {
            sql.append(" AND usage_date <= ?");
            params.add(to);
        }
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
}
