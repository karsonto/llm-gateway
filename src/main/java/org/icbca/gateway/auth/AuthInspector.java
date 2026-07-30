package org.icbca.gateway.auth;

import org.icbca.gateway.inspect.ChatRequestContext;
import org.icbca.gateway.inspect.ChatRequestInspector;
import org.icbca.gateway.inspect.InspectionResult;

/**
 * Validates API key before forwarding. Open mode (no keys configured) always allows.
 */
public final class AuthInspector implements ChatRequestInspector {

    public static final String ATTR_API_KEY = "apiKey";
    public static final String ATTR_API_KEY_NAME = "apiKeyName";

    private final ApiKeyStore apiKeyStore;

    public AuthInspector(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        String rawKey = ApiKeyStore.extractApiKey(
                ctx.getHeader("Authorization"),
                firstNonNull(ctx.getHeader("X-API-Key"), ctx.getHeader("x-api-key")));

        if (!apiKeyStore.isAuthRequired()) {
            String key = rawKey != null ? rawKey : ApiKeyStore.ANONYMOUS_KEY;
            ctx.getAttributes().put(ATTR_API_KEY, key);
            ctx.getAttributes().put(ATTR_API_KEY_NAME, apiKeyStore.resolveName(key));
            return InspectionResult.allow();
        }

        if (rawKey == null || !apiKeyStore.isValid(rawKey)) {
            return InspectionResult.deny(401, "invalid_api_key", "Missing or invalid API key");
        }

        ApiKeyInfo info = apiKeyStore.find(rawKey);
        ctx.getAttributes().put(ATTR_API_KEY, rawKey);
        ctx.getAttributes().put(ATTR_API_KEY_NAME, info != null ? info.getName() : rawKey);
        return InspectionResult.allow();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
