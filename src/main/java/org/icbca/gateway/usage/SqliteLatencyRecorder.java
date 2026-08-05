package org.icbca.gateway.usage;

import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * SQLite-backed hourly latency aggregation by model.
 */
public final class SqliteLatencyRecorder implements LatencyRecorder {

    private static final Logger log = LoggerFactory.getLogger(SqliteLatencyRecorder.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
    private static final String UNKNOWN_MODEL = "unknown";

    private final SqliteDatabase db;
    private final ZoneId zoneId;

    public SqliteLatencyRecorder(SqliteDatabase db) {
        this(db, ZoneId.systemDefault());
    }

    public SqliteLatencyRecorder(SqliteDatabase db, ZoneId zoneId) {
        if (db == null) {
            throw new IllegalArgumentException("db is null");
        }
        this.db = db;
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    @Override
    public void record(String model, final long latencyMs, final long ttftMs) {
        if (latencyMs < 0) {
            return;
        }
        final String modelName = normalizeModel(model);
        final String hourBucket = LocalDateTime.now(zoneId).format(HOUR);
        try {
            db.withConnectionVoid(new SqliteDatabase.SqlVoidWork() {
                @Override
                public void run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO latency_hourly "
                                    + "(model, hour_bucket, request_count, latency_sum_ms, "
                                    + "ttft_sum_ms, ttft_count) "
                                    + "VALUES (?, ?, 1, ?, ?, ?) "
                                    + "ON CONFLICT(model, hour_bucket) DO UPDATE SET "
                                    + "request_count = request_count + 1, "
                                    + "latency_sum_ms = latency_sum_ms + excluded.latency_sum_ms, "
                                    + "ttft_sum_ms = ttft_sum_ms + excluded.ttft_sum_ms, "
                                    + "ttft_count = ttft_count + excluded.ttft_count");
                    try {
                        ps.setString(1, modelName);
                        ps.setString(2, hourBucket);
                        ps.setLong(3, latencyMs);
                        if (ttftMs >= 0) {
                            ps.setLong(4, ttftMs);
                            ps.setLong(5, 1L);
                        } else {
                            ps.setLong(4, 0L);
                            ps.setLong(5, 0L);
                        }
                        ps.executeUpdate();
                    } finally {
                        ps.close();
                    }
                }
            });
            log.debug("latency recorded model={} hour={} latencyMs={} ttftMs={}",
                    modelName, hourBucket, latencyMs, ttftMs);
        } catch (SQLException e) {
            log.warn("latency record failed model={}: {}", modelName, e.getMessage());
        }
    }

    private static String normalizeModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            return UNKNOWN_MODEL;
        }
        return model.trim();
    }
}
