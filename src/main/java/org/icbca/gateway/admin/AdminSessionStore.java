package org.icbca.gateway.admin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory admin session tokens.
 */
public final class AdminSessionStore {

    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<String, Long>();

    public String createSession() {
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis());
        return token;
    }

    public boolean isValid(String token) {
        return token != null && !token.isEmpty() && tokens.containsKey(token);
    }

    public void invalidate(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    public void clear() {
        tokens.clear();
    }

    public int size() {
        return tokens.size();
    }
}
