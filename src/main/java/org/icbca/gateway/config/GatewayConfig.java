package org.icbca.gateway.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    public GatewayConfig(int port, String vllmHost, int vllmPort, int maxContentLength,
                         Set<String> pathWhitelist) {
        this.port = port;
        this.vllmHost = vllmHost;
        this.vllmPort = vllmPort;
        this.maxContentLength = maxContentLength;
        this.pathWhitelist = Collections.unmodifiableSet(new LinkedHashSet<String>(pathWhitelist));
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
        return new GatewayConfig(port, vllmHost, vllmPort, maxContentLength, whitelist);
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

    @Override
    public String toString() {
        return "GatewayConfig{port=" + port
                + ", vllm=" + vllmHost + ":" + vllmPort
                + ", maxContentLength=" + maxContentLength
                + ", pathWhitelist=" + Arrays.toString(pathWhitelist.toArray())
                + '}';
    }
}
