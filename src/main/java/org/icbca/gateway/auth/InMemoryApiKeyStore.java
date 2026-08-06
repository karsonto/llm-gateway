package org.icbca.gateway.auth;

import org.icbca.gateway.config.GatewayConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory API key registry loaded from {@link GatewayConfig}.
 * Empty key set means open mode (auth not required).
 * Swap for a DB-backed {@link ApiKeyStore} when needed.
 */
public final class InMemoryApiKeyStore implements ApiKeyStore {

    private final Map<String, ApiKeyInfo> keys;
    private final boolean authRequired;

    public InMemoryApiKeyStore(GatewayConfig config) {
        Map<String, ApiKeyInfo> map = new LinkedHashMap<String, ApiKeyInfo>();
        for (Map.Entry<String, String> e : config.getApiKeys().entrySet()) {
            map.put(e.getKey(), new ApiKeyInfo(e.getKey(), e.getValue(), true));
        }
        this.keys = Collections.unmodifiableMap(map);
        this.authRequired = !this.keys.isEmpty();
    }

    @Override
    public boolean isAuthRequired() {
        return authRequired;
    }

    @Override
    public ApiKeyInfo find(String key) {
        if (key == null) {
            return null;
        }
        return keys.get(key);
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
    public String resolveDepartment(String key) {
        if (key == null || ANONYMOUS_KEY.equals(key)) {
            return ANONYMOUS_KEY;
        }
        ApiKeyInfo info = find(key);
        if (info != null) {
            return info.getDepartment();
        }
        return "FTD";
    }

    @Override
    public Map<String, ApiKeyInfo> getKeys() {
        return keys;
    }
}
