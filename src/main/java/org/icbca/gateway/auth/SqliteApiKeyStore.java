package org.icbca.gateway.auth;

import org.icbca.gateway.db.SqliteDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed {@link ApiKeyStore}. Empty {@code api_keys} table means open mode.
 */
public final class SqliteApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteApiKeyStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

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
                    return loadOne(connection, key);
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
                    for (ApiKeyInfo info : listAll(connection)) {
                        map.put(info.getKey(), info);
                    }
                    return Collections.unmodifiableMap(map);
                }
            });
        } catch (SQLException e) {
            log.warn("getKeys failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public List<ApiKeyInfo> list() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<List<ApiKeyInfo>>() {
                @Override
                public List<ApiKeyInfo> run(java.sql.Connection connection) throws SQLException {
                    return listAll(connection);
                }
            });
        } catch (SQLException e) {
            log.warn("list keys failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Paginated list with optional filters.
     *
     * @param q       fuzzy match on name or api_key (null/blank = no filter)
     * @param group   exact group_name (null/blank = no filter)
     * @param enabled null = no filter; otherwise filter by enabled flag
     */
    public List<ApiKeyInfo> listPage(final String q, final String group, final Boolean enabled,
                                     final int offset, final int limit) {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<List<ApiKeyInfo>>() {
                @Override
                public List<ApiKeyInfo> run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder(
                            "SELECT api_key, name, group_name, enabled FROM api_keys");
                    List<Object> params = new ArrayList<Object>();
                    appendFilters(sql, params, q, group, enabled);
                    sql.append(" ORDER BY id LIMIT ? OFFSET ?");
                    params.add(Integer.valueOf(limit));
                    params.add(Integer.valueOf(offset));

                    PreparedStatement ps = connection.prepareStatement(sql.toString());
                    try {
                        bindParams(ps, params);
                        ResultSet rs = ps.executeQuery();
                        try {
                            List<ApiKeyInfo> list = new ArrayList<ApiKeyInfo>();
                            while (rs.next()) {
                                list.add(new ApiKeyInfo(
                                        rs.getString("api_key"),
                                        rs.getString("name"),
                                        rs.getString("group_name"),
                                        rs.getInt("enabled") != 0));
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
        } catch (SQLException e) {
            log.warn("listPage keys failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public int countPage(final String q, final String group, final Boolean enabled) {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<Integer>() {
                @Override
                public Integer run(java.sql.Connection connection) throws SQLException {
                    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM api_keys");
                    List<Object> params = new ArrayList<Object>();
                    appendFilters(sql, params, q, group, enabled);
                    PreparedStatement ps = connection.prepareStatement(sql.toString());
                    try {
                        bindParams(ps, params);
                        ResultSet rs = ps.executeQuery();
                        try {
                            if (rs.next()) {
                                return Integer.valueOf(rs.getInt(1));
                            }
                            return Integer.valueOf(0);
                        } finally {
                            rs.close();
                        }
                    } finally {
                        ps.close();
                    }
                }
            }).intValue();
        } catch (SQLException e) {
            log.warn("countPage keys failed: {}", e.getMessage());
            return 0;
        }
    }

    private static void appendFilters(StringBuilder sql, List<Object> params,
                                      String q, String group, Boolean enabled) {
        List<String> clauses = new ArrayList<String>();
        if (q != null && !q.trim().isEmpty()) {
            String like = "%" + escapeLike(q.trim()) + "%";
            clauses.add("(name LIKE ? ESCAPE '\\' OR api_key LIKE ? ESCAPE '\\')");
            params.add(like);
            params.add(like);
        }
        if (group != null && !group.trim().isEmpty()) {
            clauses.add("group_name = ?");
            params.add(group.trim());
        }
        if (enabled != null) {
            clauses.add("enabled = ?");
            params.add(Integer.valueOf(enabled.booleanValue() ? 1 : 0));
        }
        if (!clauses.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < clauses.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(clauses.get(i));
            }
        }
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer) {
                ps.setInt(i + 1, ((Integer) p).intValue());
            } else {
                ps.setString(i + 1, String.valueOf(p));
            }
        }
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Insert a new API key. If {@code apiKey} is null/blank, generates {@code sk-} + random.
     */
    public ApiKeyInfo create(String apiKey, String name, String groupName, boolean enabled)
            throws SQLException {
        final String key = (apiKey == null || apiKey.trim().isEmpty())
                ? generateApiKey() : apiKey.trim();
        final String nm = (name == null || name.trim().isEmpty()) ? key : name.trim();
        final String group = (groupName == null || groupName.trim().isEmpty())
                ? "default" : groupName.trim();
        final int en = enabled ? 1 : 0;

        return db.withConnection(new SqliteDatabase.SqlWork<ApiKeyInfo>() {
            @Override
            public ApiKeyInfo run(java.sql.Connection connection) throws SQLException {
                if (loadOne(connection, key) != null) {
                    throw new SQLException("api_key already exists: " + key);
                }
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO api_keys (api_key, name, group_name, enabled, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))");
                try {
                    ps.setString(1, key);
                    ps.setString(2, nm);
                    ps.setString(3, group);
                    ps.setInt(4, en);
                    ps.executeUpdate();
                } finally {
                    ps.close();
                }
                return new ApiKeyInfo(key, nm, group, enabled);
            }
        });
    }

    public ApiKeyInfo update(String apiKey, String name, String groupName, Boolean enabled)
            throws SQLException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new SQLException("api_key is required");
        }
        final String key = apiKey.trim();
        return db.withConnection(new SqliteDatabase.SqlWork<ApiKeyInfo>() {
            @Override
            public ApiKeyInfo run(java.sql.Connection connection) throws SQLException {
                ApiKeyInfo existing = loadOne(connection, key);
                if (existing == null) {
                    throw new SQLException("api_key not found: " + key);
                }
                String nm = name != null && !name.trim().isEmpty() ? name.trim() : existing.getName();
                String group = groupName != null && !groupName.trim().isEmpty()
                        ? groupName.trim() : existing.getGroupName();
                boolean en = enabled != null ? enabled.booleanValue() : existing.isEnabled();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE api_keys SET name = ?, group_name = ?, enabled = ?, "
                                + "updated_at = datetime('now') WHERE api_key = ?");
                try {
                    ps.setString(1, nm);
                    ps.setString(2, group);
                    ps.setInt(3, en ? 1 : 0);
                    ps.setString(4, key);
                    ps.executeUpdate();
                } finally {
                    ps.close();
                }
                return new ApiKeyInfo(key, nm, group, en);
            }
        });
    }

    public List<String> listGroupNames() {
        try {
            return db.withConnection(new SqliteDatabase.SqlWork<List<String>>() {
                @Override
                public List<String> run(java.sql.Connection connection) throws SQLException {
                    List<String> list = new ArrayList<String>();
                    Statement st = connection.createStatement();
                    try {
                        ResultSet rs = st.executeQuery(
                                "SELECT DISTINCT group_name FROM api_keys ORDER BY group_name");
                        try {
                            while (rs.next()) {
                                list.add(rs.getString(1));
                            }
                        } finally {
                            rs.close();
                        }
                    } finally {
                        st.close();
                    }
                    return list;
                }
            });
        } catch (SQLException e) {
            log.warn("listGroupNames failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static ApiKeyInfo loadOne(java.sql.Connection connection, String key) throws SQLException {
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

    private static List<ApiKeyInfo> listAll(java.sql.Connection connection) throws SQLException {
        List<ApiKeyInfo> list = new ArrayList<ApiKeyInfo>();
        Statement st = connection.createStatement();
        try {
            ResultSet rs = st.executeQuery(
                    "SELECT api_key, name, group_name, enabled FROM api_keys ORDER BY id");
            try {
                while (rs.next()) {
                    list.add(new ApiKeyInfo(
                            rs.getString("api_key"),
                            rs.getString("name"),
                            rs.getString("group_name"),
                            rs.getInt("enabled") != 0));
                }
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
        return list;
    }

    public static String generateApiKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("sk-");
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }
}
