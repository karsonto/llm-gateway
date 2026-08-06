package org.icbca.gateway.auth;

import java.util.Map;

/**
 * API key lookup used for auth and usage attribution.
 * <p>
 * MVP: {@link InMemoryApiKeyStore} loaded from config. Replace with a DB-backed
 * implementation later without changing call sites.
 */
public interface ApiKeyStore {

    String ANONYMOUS_KEY = "anonymous";

    /**
     * {@code true} when keys are configured / present and requests must present a valid key.
     * Empty store means open mode (auth not required).
     */
    boolean isAuthRequired();

    ApiKeyInfo find(String key);

    boolean isValid(String key);

    String resolveName(String key);

    /**
     * Display group for the key. Unknown / anonymous keys return {@code "anonymous"} or {@code "default"}.
     */
    String resolveGroupName(String key);

    /**
     * Display department for the key. Unknown / anonymous keys return {@code "FTD"} or anonymous.
     */
    String resolveDepartment(String key);

    Map<String, ApiKeyInfo> getKeys();

    /**
     * Extracts API key from Authorization Bearer or X-API-Key header values.
     */
    static String extractApiKey(String authorization, String xApiKey) {
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
