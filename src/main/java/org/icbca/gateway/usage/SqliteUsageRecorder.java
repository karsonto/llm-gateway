package org.icbca.gateway.usage;

import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SQLite-backed usage aggregation by apiKey + date + model.
 */
public final class SqliteUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SqliteUsageRecorder.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String UNKNOWN_MODEL = "unknown";

    private final SqliteDatabase db;
    private final ApiKeyStore apiKeyStore;
    private final ZoneId zoneId;

    public SqliteUsageRecorder(SqliteDatabase db, ApiKeyStore apiKeyStore) {
        this(db, apiKeyStore, ZoneId.systemDefault());
    }

    public SqliteUsageRecorder(SqliteDatabase db, ApiKeyStore apiKeyStore, ZoneId zoneId) {
        if (db == null) {
            throw new IllegalArgumentException("db is null");
        }
        this.db = db;
        this.apiKeyStore = apiKeyStore;
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    @Override
    public void record(String apiKey, String apiKeyName, String model, final TokenUsage usage) {
        final String key = normalizeApiKey(apiKey);
        final String modelName = normalizeModel(model);
        final String date = LocalDate.now(zoneId).format(DAY);
        final long prompt = usage == null ? 0L : usage.getPromptTokens();
        final long completion = usage == null ? 0L : usage.getCompletionTokens();
        final long total = usage == null ? 0L : usage.getTotalTokens();

        try {
            db.withConnectionVoid(new SqliteDatabase.SqlVoidWork() {
                @Override
                public void run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO usage_daily "
                                    + "(api_key, usage_date, model, request_count, prompt_tokens, "
                                    + "completion_tokens, total_tokens, updated_at) "
                                    + "VALUES (?, ?, ?, 1, ?, ?, ?, datetime('now')) "
                                    + "ON CONFLICT(api_key, usage_date, model) DO UPDATE SET "
                                    + "request_count = request_count + 1, "
                                    + "prompt_tokens = prompt_tokens + excluded.prompt_tokens, "
                                    + "completion_tokens = completion_tokens + excluded.completion_tokens, "
                                    + "total_tokens = total_tokens + excluded.total_tokens, "
                                    + "updated_at = datetime('now')");
                    try {
                        ps.setString(1, key);
                        ps.setString(2, date);
                        ps.setString(3, modelName);
                        ps.setLong(4, prompt);
                        ps.setLong(5, completion);
                        ps.setLong(6, total);
                        ps.executeUpdate();
                    } finally {
                        ps.close();
                    }
                }
            });
            log.info("usage recorded apiKey={} date={} model={} prompt={} completion={} total={}",
                    key, date, modelName, prompt, completion, total);
        } catch (SQLException e) {
            log.warn("usage record failed apiKey={}: {}", key, e.getMessage());
        }
    }

    @Override
    public List<ApiKeyUsageStats> getStats(String apiKey) {
        final String key = normalizeApiKey(apiKey);
        final String name = resolveName(key);
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<List<ApiKeyUsageStats>>() {
                @Override
                public List<ApiKeyUsageStats> run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT api_key, usage_date, model, request_count, prompt_tokens, "
                                    + "completion_tokens, total_tokens FROM usage_daily "
                                    + "WHERE api_key = ? ORDER BY usage_date DESC, model ASC");
                    try {
                        ps.setString(1, key);
                        ResultSet rs = ps.executeQuery();
                        try {
                            return mapRows(rs, name);
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("getStats failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<ApiKeyUsageStats> getAllStats() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<List<ApiKeyUsageStats>>() {
                @Override
                public List<ApiKeyUsageStats> run(java.sql.Connection connection) throws SQLException {
                    Statement st = connection.createStatement();
                    try {
                        ResultSet rs = st.executeQuery(
                                "SELECT api_key, usage_date, model, request_count, prompt_tokens, "
                                        + "completion_tokens, total_tokens FROM usage_daily "
                                        + "ORDER BY api_key ASC, usage_date DESC, model ASC");
                        try {
                            List<ApiKeyUsageStats> list = new ArrayList<ApiKeyUsageStats>();
                            while (rs.next()) {
                                String key = rs.getString("api_key");
                                list.add(new ApiKeyUsageStats(
                                        key,
                                        resolveName(key),
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
                        st.close();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("getAllStats failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public ApiKeyUsageSummary getSummary(String apiKey, String dateFilter) {
        String key = normalizeApiKey(apiKey);
        List<ApiKeyUsageStats> rows = getStats(key);
        if (dateFilter != null && !dateFilter.isEmpty()) {
            List<ApiKeyUsageStats> filtered = new ArrayList<ApiKeyUsageStats>();
            for (ApiKeyUsageStats row : rows) {
                if (dateFilter.equals(row.getDate())) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }
        String groupName = resolveGroupName(key);
        if (rows.isEmpty()) {
            return UsageSummaryBuilder.empty(key, resolveName(key), groupName);
        }
        return UsageSummaryBuilder.buildOne(rows, groupName);
    }

    @Override
    public List<ApiKeyUsageSummary> getAllSummaries(String dateFilter) {
        List<ApiKeyUsageSummary> built = UsageSummaryBuilder.buildAll(getAllStats(),
                dateFilter == null || dateFilter.isEmpty() ? null : dateFilter);
        List<ApiKeyUsageSummary> withGroup = new ArrayList<ApiKeyUsageSummary>();
        for (ApiKeyUsageSummary s : built) {
            withGroup.add(new ApiKeyUsageSummary(
                    s.getApiKey(),
                    s.getName(),
                    resolveGroupName(s.getApiKey()),
                    s.getTotal(),
                    s.getDaily()));
        }
        Collections.sort(withGroup, new Comparator<ApiKeyUsageSummary>() {
            @Override
            public int compare(ApiKeyUsageSummary a, ApiKeyUsageSummary b) {
                return a.getApiKey().compareTo(b.getApiKey());
            }
        });
        return withGroup;
    }

    private List<ApiKeyUsageStats> mapRows(ResultSet rs, String name) throws SQLException {
        List<ApiKeyUsageStats> list = new ArrayList<ApiKeyUsageStats>();
        while (rs.next()) {
            list.add(new ApiKeyUsageStats(
                    rs.getString("api_key"),
                    name,
                    rs.getString("usage_date"),
                    rs.getString("model"),
                    rs.getLong("request_count"),
                    rs.getLong("prompt_tokens"),
                    rs.getLong("completion_tokens"),
                    rs.getLong("total_tokens")));
        }
        return list;
    }

    private String resolveName(String key) {
        if (apiKeyStore != null) {
            return apiKeyStore.resolveName(key);
        }
        return key;
    }

    private String resolveGroupName(String key) {
        if (apiKeyStore != null) {
            return apiKeyStore.resolveGroupName(key);
        }
        return ApiKeyStore.ANONYMOUS_KEY.equals(key) ? ApiKeyStore.ANONYMOUS_KEY : "default";
    }

    private static String normalizeApiKey(String apiKey) {
        return apiKey == null || apiKey.isEmpty() ? ApiKeyStore.ANONYMOUS_KEY : apiKey;
    }

    private static String normalizeModel(String model) {
        return model == null || model.isEmpty() ? UNKNOWN_MODEL : model;
    }
}
