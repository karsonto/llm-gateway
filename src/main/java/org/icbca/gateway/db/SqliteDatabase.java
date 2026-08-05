package org.icbca.gateway.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared SQLite connection with schema bootstrap and a write lock for EventLoop-safe access.
 */
public final class SqliteDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteDatabase.class);

    private final String path;
    private final Connection connection;
    private final Object lock = new Object();

    private SqliteDatabase(String path, Connection connection) {
        this.path = path;
        this.connection = connection;
    }

    public static SqliteDatabase open(String path) throws SQLException, IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("sqlite path is empty");
        }
        String trimmed = path.trim();
        File file = new File(trimmed);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("failed to create sqlite parent directory: " + parent);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite JDBC driver not found", e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        conn.setAutoCommit(true);
        SqliteDatabase db = new SqliteDatabase(file.getAbsolutePath(), conn);
        db.initSchema();
        log.info("SQLite opened at {}", db.path);
        return db;
    }

    private void initSchema() throws SQLException {
        synchronized (lock) {
            Statement st = connection.createStatement();
            try {
                st.execute(
                        "CREATE TABLE IF NOT EXISTS api_keys ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "api_key TEXT NOT NULL UNIQUE,"
                                + "name TEXT NOT NULL,"
                                + "group_name TEXT NOT NULL DEFAULT 'default',"
                                + "enabled INTEGER NOT NULL DEFAULT 1,"
                                + "created_at TEXT NOT NULL DEFAULT (datetime('now')),"
                                + "updated_at TEXT NOT NULL DEFAULT (datetime('now'))"
                                + ")");
                st.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_enabled ON api_keys(enabled)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_group ON api_keys(group_name)");
                st.execute(
                        "CREATE TABLE IF NOT EXISTS usage_daily ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "api_key TEXT NOT NULL,"
                                + "usage_date TEXT NOT NULL,"
                                + "model TEXT NOT NULL,"
                                + "request_count INTEGER NOT NULL DEFAULT 0,"
                                + "prompt_tokens INTEGER NOT NULL DEFAULT 0,"
                                + "completion_tokens INTEGER NOT NULL DEFAULT 0,"
                                + "total_tokens INTEGER NOT NULL DEFAULT 0,"
                                + "updated_at TEXT NOT NULL DEFAULT (datetime('now')),"
                                + "UNIQUE (api_key, usage_date, model)"
                                + ")");
                st.execute(
                        "CREATE INDEX IF NOT EXISTS idx_usage_daily_key_date "
                                + "ON usage_daily(api_key, usage_date)");
                st.execute(
                        "CREATE INDEX IF NOT EXISTS idx_usage_daily_date ON usage_daily(usage_date)");
                st.execute(
                        "CREATE TABLE IF NOT EXISTS latency_hourly ("
                                + "model TEXT NOT NULL,"
                                + "hour_bucket TEXT NOT NULL,"
                                + "request_count INTEGER NOT NULL DEFAULT 0,"
                                + "latency_sum_ms INTEGER NOT NULL DEFAULT 0,"
                                + "ttft_sum_ms INTEGER NOT NULL DEFAULT 0,"
                                + "ttft_count INTEGER NOT NULL DEFAULT 0,"
                                + "UNIQUE (model, hour_bucket)"
                                + ")");
                st.execute(
                        "CREATE INDEX IF NOT EXISTS idx_latency_hourly_bucket "
                                + "ON latency_hourly(hour_bucket)");
                st.execute(
                        "CREATE INDEX IF NOT EXISTS idx_latency_hourly_model "
                                + "ON latency_hourly(model)");
            } finally {
                st.close();
            }
        }
    }

    public String getPath() {
        return path;
    }

    /**
     * Runs work holding the DB lock. Callers must not hold ResultSets beyond the callback.
     */
    public <T> T withConnection(SqlWork<T> work) throws SQLException {
        synchronized (lock) {
            return work.run(connection);
        }
    }

    public void withConnectionVoid(SqlVoidWork work) throws SQLException {
        synchronized (lock) {
            work.run(connection);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    log.info("SQLite closed: {}", path);
                }
            } catch (SQLException e) {
                log.warn("failed to close sqlite: {}", e.getMessage());
            }
        }
    }

    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    public interface SqlVoidWork {
        void run(Connection connection) throws SQLException;
    }
}
