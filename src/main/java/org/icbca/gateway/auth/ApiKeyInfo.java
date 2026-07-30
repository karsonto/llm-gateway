package org.icbca.gateway.auth;

/**
 * Registered API key metadata.
 */
public final class ApiKeyInfo {

    private final String key;
    private final String name;
    private final boolean enabled;

    public ApiKeyInfo(String key, String name, boolean enabled) {
        this.key = key;
        this.name = name;
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
