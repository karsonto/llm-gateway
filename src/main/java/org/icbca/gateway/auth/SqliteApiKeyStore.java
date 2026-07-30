package org.icbca.gateway.auth;

import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite-backed {@link ApiKeyStore}. Empty {@code api_keys} table means open mode.
 */
public final class SqliteApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteApiKeyStore.class);

    private final SqliteDatabase db;

    public SqliteApiKeyStore(SqliteDatabase db) {
        if (db == null) {
            throw new IllegalArgumentException("db is null");
        }
        this.db = db;
    }

    @Override
    public boolean isAuthRequired() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<Boolean>() {
                @Override
                public Boolean run(java.sql.Connection connection) throws SQLException {
                    Statement st = connection.createStatement();
                    try {
                        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM api_keys");
                        try {
                            if (rs.next()) {
                                return rs.getInt(1) > 0;
                            }
                            return false;
                        } finally {
                            rs.close();
                        }
                    } finally {
                        st.close();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("isAuthRequired failed: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public ApiKeyInfo find(final String key) {
        if (key == null) {
            return null;
        }
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<ApiKeyInfo>() {
                @Override
                public ApiKeyInfo run(java.sql.Connection connection) throws SQLException {
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT api_key, name, group_name, enabled FROM api_keys WHERE api_key = ?");
                    try {
                        ps.setString(1, key);
                        ResultSet rs = ps.executeQuery();
                        try {
                            if (!rs.next()) {
                                return null;
                            }
                            return new ApiKeyInfo(
                                    rs.getString("api_key"),
                                    rs.getString("name"),
                                    rs.getString("group_name"),
                                    rs.getInt("enabled") != 0);
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            });
        } catch (SQLException e) {
            log.warn("find api key failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean isValid(String key) {
        ApiKeyInfo info = find(key);
        return info != null && info.isEnabled();
    }

    @Override
    public String resolveName(String key) {
        if (key == null) {
            return ANONYMOUS_KEY;
        }
        ApiKeyInfo info = find(key);
        if (info != null) {
            return info.getName();
        }
        return key;
    }

    @Override
    public String resolveGroupName(String key) {
        if (key == null || ANONYMOUS_KEY.equals(key)) {
            return ANONYMOUS_KEY;
        }
        ApiKeyInfo info = find(key);
        if (info != null) {
            return info.getGroupName();
        }
        return "default";
    }

    @Override
    public Map<String, ApiKeyInfo> getKeys() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<Map<String, ApiKeyInfo>>() {
                @Override
                public Map<String, ApiKeyInfo> run(java.sql.Connection connection) throws SQLException {
                    Map<String, ApiKeyInfo> map = new LinkedHashMap<String, ApiKeyInfo>();
                    Statement st = connection.createStatement();
                    try {
                        ResultSet rs = st.executeQuery(
                                "SELECT api_key, name, group_name, enabled FROM api_keys ORDER BY id");
                        try {
                            while (rs.next()) {
                                ApiKeyInfo info = new ApiKeyInfo(
                                        rs.getString("api_key"),
                                        rs.getString("name"),
                                        rs.getString("group_name"),
                                        rs.getInt("enabled") != 0);
                                map.put(info.getKey(), info);
                            }
                        } finally {
                            rs.close();
                        }
                    } finally {
                        st.close();
                    }
                    return Collections.unmodifiableMap(map);
                }
            });
        } catch (SQLException e) {
            log.warn("getKeys failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
