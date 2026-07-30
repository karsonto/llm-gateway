package org.icbca.gateway.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Immutable gateway configuration loaded at startup.
 */
public final class GatewayConfig {

    private final int port;
    private final String vllmHost;
    private final int vllmPort;
    private final int maxContentLength;
    private final Set<String> pathWhitelist;
    /** key -> optional display name; empty map means open auth mode (memory mode only). */
    private final Map<String, String> apiKeys;
    /**
     * SQLite file path. Non-empty enables DB-backed ApiKeyStore + UsageRecorder
     * and ignores {@code gateway.api.keys}.
     */
    private final String sqlitePath;

    public GatewayConfig(int port, String vllmHost, int vllmPort, int maxContentLength,
                         Set<String> pathWhitelist, Map<String, String> apiKeys, String sqlitePath) {
        this.port = port;
        this.vllmHost = vllmHost;
        this.vllmPort = vllmPort;
        this.maxContentLength = maxContentLength;
        this.pathWhitelist = Collections.unmodifiableSet(new LinkedHashSet<String>(pathWhitelist));
        this.apiKeys = Collections.unmodifiableMap(new LinkedHashMap<String, String>(apiKeys));
        this.sqlitePath = sqlitePath == null ? "" : sqlitePath.trim();
    }

    public static GatewayConfig load() throws IOException {
        Properties props = new Properties();
        InputStream in = GatewayConfig.class.getClassLoader().getResourceAsStream("gateway.properties");
        if (in == null) {
            throw new IOException("classpath:gateway.properties not found");
        }
        try {
            props.load(in);
        } finally {
            in.close();
        }
        return fromProperties(props);
    }

    public static GatewayConfig fromProperties(Properties props) {
        int port = Integer.parseInt(props.getProperty("gateway.port", "8080").trim());
        String vllmHost = props.getProperty("vllm.host", "127.0.0.1").trim();
        int vllmPort = Integer.parseInt(props.getProperty("vllm.port", "8000").trim());
        int maxContentLength = Integer.parseInt(
                props.getProperty("gateway.maxContentLength", "10485760").trim());
        String whitelistRaw = props.getProperty("gateway.path.whitelist", "").trim();
        Set<String> whitelist = new LinkedHashSet<String>();
        if (!whitelistRaw.isEmpty()) {
            for (String part : whitelistRaw.split(",")) {
                String path = part.trim();
                if (!path.isEmpty()) {
                    whitelist.add(path);
                }
            }
        }
        Map<String, String> apiKeys = parseApiKeys(props.getProperty("gateway.api.keys", ""));
        String sqlitePath = props.getProperty("gateway.sqlite.path", "").trim();
        return new GatewayConfig(port, vllmHost, vllmPort, maxContentLength, whitelist, apiKeys, sqlitePath);
    }

    /**
     * Parses {@code key} or {@code key:name} entries separated by commas.
     */
    static Map<String, String> parseApiKeys(String raw) {
        Map<String, String> keys = new LinkedHashMap<String, String>();
        if (raw == null) {
            return keys;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return keys;
        }
        for (String part : trimmed.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                keys.put(entry, entry);
            } else {
                String key = entry.substring(0, colon).trim();
                String name = entry.substring(colon + 1).trim();
                if (!key.isEmpty()) {
                    keys.put(key, name.isEmpty() ? key : name);
                }
            }
        }
        return keys;
    }

    public int getPort() {
        return port;
    }

    public String getVllmHost() {
        return vllmHost;
    }

    public int getVllmPort() {
        return vllmPort;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public Set<String> getPathWhitelist() {
        return pathWhitelist;
    }

    public boolean isPathAllowed(String path) {
        return pathWhitelist.contains(path);
    }

    /**
     * Configured API keys (key -> display name). Empty means open mode in memory mode.
     * Ignored when {@link #isSqliteEnabled()}.
     */
    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public boolean isSqliteEnabled() {
        return sqlitePath != null && !sqlitePath.isEmpty();
    }

    @Override
    public String toString() {
        return "GatewayConfig{port=" + port
                + ", vllm=" + vllmHost + ":" + vllmPort
                + ", maxContentLength=" + maxContentLength
                + ", pathWhitelist=" + Arrays.toString(pathWhitelist.toArray())
                + ", sqlitePath=" + (sqlitePath.isEmpty() ? "(none)" : sqlitePath)
                + ", apiKeysConfigured=" + !apiKeys.isEmpty()
                + ", apiKeyCount=" + apiKeys.size()
                + '}';
    }
}
