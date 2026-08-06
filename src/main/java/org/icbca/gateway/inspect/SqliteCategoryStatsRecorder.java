package org.icbca.gateway.inspect;

import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * SQLite-backed daily category counts per API key.
 */
public final class SqliteCategoryStatsRecorder implements CategoryStatsRecorder {

    private static final Logger log = LoggerFactory.getLogger(SqliteCategoryStatsRecorder.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final SqliteDatabase db;
    private final ZoneId zoneId;

    public SqliteCategoryStatsRecorder(SqliteDatabase db) {
        this(db, ZoneId.systemDefault());
    }

    public SqliteCategoryStatsRecorder(SqliteDatabase db, ZoneId zoneId) {
        if (db == null) {
            throw new IllegalArgumentException("db is null");
        }
        this.db = db;
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    @Override
    public void record(String apiKey, String name, String category) {
        final String key = apiKey == null || apiKey.trim().isEmpty()
                ? ApiKeyStore.ANONYMOUS_KEY : apiKey.trim();
        final String displayName = name == null || name.trim().isEmpty() ? key : name.trim();
        final String cat = category == null || category.trim().isEmpty()
                ? CategoryClient.CATEGORY_ERROR : category.trim();
        final String date = LocalDate.now(zoneId).format(DAY);
        try {
            db.withConnectionVoid(new SqliteDatabase.SqlVoidWork() {
                @Override
                public void run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO category_daily "
                                    + "(api_key, name, category, usage_date, request_count) "
                                    + "VALUES (?, ?, ?, ?, 1) "
                                    + "ON CONFLICT(api_key, category, usage_date) DO UPDATE SET "
                                    + "request_count = request_count + 1, "
                                    + "name = excluded.name");
                    try {
                        ps.setString(1, key);
                        ps.setString(2, displayName);
                        ps.setString(3, cat);
                        ps.setString(4, date);
                        ps.executeUpdate();
                    } finally {
                        ps.close();
                    }
                }
            });
            log.debug("category recorded key={} name={} category={} date={}",
                    key, displayName, cat, date);
        } catch (SQLException e) {
            log.warn("category record failed key={}: {}", key, e.getMessage());
        }
    }
}
