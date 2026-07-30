package org.icbca.gateway.auth;

import org.icbca.gateway.config.GatewayConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory API key registry loaded from {@link GatewayConfig}.
 * Empty key set means open mode (auth not required).
 */
public final class ApiKeyStore {

    public static final String ANONYMOUS_KEY = "anonymous";

    private final Map<String, ApiKeyInfo> keys;
    private final boolean authRequired;

    public ApiKeyStore(GatewayConfig config) {
        Map<String, ApiKeyInfo> map = new LinkedHashMap<String, ApiKeyInfo>();
        for (Map.Entry<String, String> e : config.getApiKeys().entrySet()) {
            map.put(e.getKey(), new ApiKeyInfo(e.getKey(), e.getValue(), true));
        }
        this.keys = Collections.unmodifiableMap(map);
        this.authRequired = !this.keys.isEmpty();
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public ApiKeyInfo find(String key) {
        if (key == null) {
            return null;
        }
        return keys.get(key);
    }

    public boolean isValid(String key) {
        ApiKeyInfo info = find(key);
        return info != null && info.isEnabled();
    }

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

    public Map<String, ApiKeyInfo> getKeys() {
        return keys;
    }

    /**
     * Extracts API key from Authorization Bearer or X-API-Key header values.
     */
    public static String extractApiKey(String authorization, String xApiKey) {
        if (xApiKey != null) {
            String trimmed = xApiKey.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        if (authorization != null) {
            String trimmed = authorization.trim();
            if (trimmed.length() >= 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = trimmed.substring(7).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        return null;
    }
}
