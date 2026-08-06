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
 * SQLite-backed hourly latency aggregation by model, plus histogram bins for percentiles.
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
    public void record(String model, final LatencySample sample) {
        if (sample == null || sample.getLatencyMs() < 0) {
            return;
        }
        final String modelName = normalizeModel(model);
        final String hourBucket = LocalDateTime.now(zoneId).format(HOUR);
        final long latencyMs = sample.getLatencyMs();
        final long ttftMs = sample.getTtftMs();
        final long tpotMs = sample.getTpotMs();
        final long itlMs = sample.getItlMs();
        final long outputTpsMilli = sample.getOutputTpsMilli();
        final long promptTokens = Math.max(0L, sample.getPromptTokens());
        final long completionTokens = Math.max(0L, sample.getCompletionTokens());
        try {
            db.withConnectionVoid(new SqliteDatabase.SqlVoidWork() {
                @Override
                public void run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO latency_hourly "
                                    + "(model, hour_bucket, request_count, latency_sum_ms, "
                                    + "latency_max_ms, ttft_sum_ms, ttft_count, "
                                    + "tpot_sum_ms, tpot_count, itl_sum_ms, itl_count, "
                                    + "output_tps_milli_sum, output_tps_count, "
                                    + "prompt_tokens_sum, completion_tokens_sum) "
                                    + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT(model, hour_bucket) DO UPDATE SET "
                                    + "request_count = request_count + 1, "
                                    + "latency_sum_ms = latency_sum_ms + excluded.latency_sum_ms, "
                                    + "latency_max_ms = MAX(latency_max_ms, excluded.latency_max_ms), "
                                    + "ttft_sum_ms = ttft_sum_ms + excluded.ttft_sum_ms, "
                                    + "ttft_count = ttft_count + excluded.ttft_count, "
                                    + "tpot_sum_ms = tpot_sum_ms + excluded.tpot_sum_ms, "
                                    + "tpot_count = tpot_count + excluded.tpot_count, "
                                    + "itl_sum_ms = itl_sum_ms + excluded.itl_sum_ms, "
                                    + "itl_count = itl_count + excluded.itl_count, "
                                    + "output_tps_milli_sum = output_tps_milli_sum + excluded.output_tps_milli_sum, "
                                    + "output_tps_count = output_tps_count + excluded.output_tps_count, "
                                    + "prompt_tokens_sum = prompt_tokens_sum + excluded.prompt_tokens_sum, "
                                    + "completion_tokens_sum = completion_tokens_sum + excluded.completion_tokens_sum");
                    try {
                        ps.setString(1, modelName);
                        ps.setString(2, hourBucket);
                        ps.setLong(3, latencyMs);
                        ps.setLong(4, latencyMs);
                        setOptional(ps, 5, 6, ttftMs);
                        setOptional(ps, 7, 8, tpotMs);
                        setOptional(ps, 9, 10, itlMs);
                        if (outputTpsMilli >= 0) {
                            ps.setLong(11, outputTpsMilli);
                            ps.setLong(12, 1L);
                        } else {
                            ps.setLong(11, 0L);
                            ps.setLong(12, 0L);
                        }
                        ps.setLong(13, promptTokens);
                        ps.setLong(14, completionTokens);
                        ps.executeUpdate();
                    } finally {
                        ps.close();
                    }

                    if (ttftMs >= 0) {
                        bumpHist(connection, modelName, hourBucket, LatencyHistBins.METRIC_TTFT, ttftMs);
                    }
                    if (tpotMs >= 0) {
                        bumpHist(connection, modelName, hourBucket, LatencyHistBins.METRIC_TPOT, tpotMs);
                    }
                    if (itlMs >= 0) {
                        bumpHist(connection, modelName, hourBucket, LatencyHistBins.METRIC_ITL, itlMs);
                    }
                }
            });
            log.debug("latency recorded model={} hour={} latencyMs={} ttftMs={} tpotMs={} itlMs={}",
                    modelName, hourBucket, latencyMs, ttftMs, tpotMs, itlMs);
        } catch (SQLException e) {
            log.warn("latency record failed model={}: {}", modelName, e.getMessage());
        }
    }

    private static void setOptional(PreparedStatement ps, int sumIdx, int countIdx, long value)
            throws SQLException {
        if (value >= 0) {
            ps.setLong(sumIdx, value);
            ps.setLong(countIdx, 1L);
        } else {
            ps.setLong(sumIdx, 0L);
            ps.setLong(countIdx, 0L);
        }
    }

    private static void bumpHist(java.sql.Connection connection, String model, String hourBucket,
                                 String metric, long valueMs) throws SQLException {
        long bin = LatencyHistBins.binFor(valueMs);
        PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO latency_hist_hourly (model, hour_bucket, metric, bin, cnt) "
                        + "VALUES (?, ?, ?, ?, 1) "
                        + "ON CONFLICT(model, hour_bucket, metric, bin) DO UPDATE SET "
                        + "cnt = cnt + 1");
        try {
            ps.setString(1, model);
            ps.setString(2, hourBucket);
            ps.setString(3, metric);
            ps.setLong(4, bin);
            ps.executeUpdate();
        } finally {
            ps.close();
        }
    }

    private static String normalizeModel(String model) {
        if (model == null || model.trim().isEmpty()) {
            return UNKNOWN_MODEL;
        }
        return model.trim();
    }
}
