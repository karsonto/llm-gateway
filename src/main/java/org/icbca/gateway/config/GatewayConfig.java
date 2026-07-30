package org.icbca.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
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
 * <p>
 * Loads defaults from classpath {@code gateway.properties}, then overlays an external file when set via
 * environment variable {@code GATEWAY_CONFIG} or JVM property {@code gateway.config}.
 */
public final class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);
    public static final String ENV_CONFIG_PATH = "GATEWAY_CONFIG";
    public static final String SYS_CONFIG_PATH = "gateway.config";

    private final int port;
    private final String vllmHost;
    private final int vllmPort;
    private final int maxContentLength;
    private final Set<String> pathWhitelist;
    private final Map<String, String> apiKeys;
    private final String sqlitePath;
    private final String adminUsername;
    private final String adminPassword;
    private final String configSource;

    public GatewayConfig(int port, String vllmHost, int vllmPort, int maxContentLength,
                         Set<String> pathWhitelist, Map<String, String> apiKeys, String sqlitePath,
                         String adminUsername, String adminPassword, String configSource) {
        this.port = port;
        this.vllmHost = vllmHost;
        this.vllmPort = vllmPort;
        this.maxContentLength = maxContentLength;
        this.pathWhitelist = Collections.unmodifiableSet(new LinkedHashSet<String>(pathWhitelist));
        this.apiKeys = Collections.unmodifiableMap(new LinkedHashMap<String, String>(apiKeys));
        this.sqlitePath = sqlitePath == null ? "" : sqlitePath.trim();
        this.adminUsername = adminUsername == null || adminUsername.trim().isEmpty()
                ? "admin" : adminUsername.trim();
        this.adminPassword = adminPassword == null || adminPassword.isEmpty()
                ? "admin123" : adminPassword;
        this.configSource = configSource == null ? "classpath:gateway.properties" : configSource;
    }

    public static GatewayConfig load() throws IOException {
        Properties props = new Properties();
        boolean hasClasspath = loadClasspathDefaults(props);

        String externalPath = resolveExternalConfigPath();
        boolean hasExternal = false;
        if (externalPath != null && !externalPath.isEmpty()) {
            File file = new File(externalPath);
            if (file.isFile()) {
                FileInputStream in = new FileInputStream(file);
                try {
                    props.load(in);
                    hasExternal = true;
                } finally {
                    in.close();
                }
            } else if (hasClasspath) {
                log.warn("External config not found at {}, using classpath defaults", file.getAbsolutePath());
            } else {
                throw new IOException("External config not found: " + file.getAbsolutePath());
            }
        }

        if (!hasClasspath && !hasExternal) {
            throw new IOException("No gateway.properties on classpath and no external config specified");
        }

        String source;
        if (hasExternal) {
            source = "file:" + new File(externalPath).getAbsolutePath()
                    + (hasClasspath ? " (over classpath defaults)" : "");
            log.info("Loaded gateway config from {}", source);
        } else {
            source = "classpath:gateway.properties";
            log.info("Loaded gateway config from {}", source);
        }

        return fromProperties(props, source);
    }

    /**
     * External config path: JVM {@code -Dgateway.config=...} then env {@code GATEWAY_CONFIG}.
     */
    static String resolveExternalConfigPath() {
        String sys = System.getProperty(SYS_CONFIG_PATH);
        if (sys != null && !sys.trim().isEmpty()) {
            return sys.trim();
        }
        String env = System.getenv(ENV_CONFIG_PATH);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return null;
    }

    private static boolean loadClasspathDefaults(Properties props) throws IOException {
        InputStream in = GatewayConfig.class.getClassLoader().getResourceAsStream("gateway.properties");
        if (in == null) {
            return false;
        }
        try {
            props.load(in);
            return true;
        } finally {
            in.close();
        }
    }

    public static GatewayConfig fromProperties(Properties props) {
        return fromProperties(props, "classpath:gateway.properties");
    }

    public static GatewayConfig fromProperties(Properties props, String configSource) {
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
        String adminUsername = props.getProperty("gateway.admin.username", "admin").trim();
        String adminPassword = props.getProperty("gateway.admin.password", "admin123");
        return new GatewayConfig(port, vllmHost, vllmPort, maxContentLength, whitelist, apiKeys,
                sqlitePath, adminUsername, adminPassword, configSource);
    }

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

    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public boolean isSqliteEnabled() {
        return sqlitePath != null && !sqlitePath.isEmpty();
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public String getConfigSource() {
        return configSource;
    }

    @Override
    public String toString() {
        return "GatewayConfig{port=" + port
                + ", vllm=" + vllmHost + ":" + vllmPort
                + ", maxContentLength=" + maxContentLength
                + ", pathWhitelist=" + Arrays.toString(pathWhitelist.toArray())
                + ", sqlitePath=" + (sqlitePath.isEmpty() ? "(none)" : sqlitePath)
                + ", adminUsername=" + adminUsername
                + ", configSource=" + configSource
                + ", apiKeysConfigured=" + !apiKeys.isEmpty()
                + ", apiKeyCount=" + apiKeys.size()
                + '}';
    }
}
