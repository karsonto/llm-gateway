package org.icbca.gateway.auth;

/**
 * Registered API key metadata.
 */
public final class ApiKeyInfo {

    private final String key;
    private final String name;
    private final String groupName;
    private final boolean enabled;
    /** Absolute token count; {@code 0} means unlimited. */
    private final long monthlyTokenLimit;

    public ApiKeyInfo(String key, String name, boolean enabled) {
        this(key, name, "default", enabled, 0L);
    }

    public ApiKeyInfo(String key, String name, String groupName, boolean enabled) {
        this(key, name, groupName, enabled, 0L);
    }

    public ApiKeyInfo(String key, String name, String groupName, boolean enabled,
                      long monthlyTokenLimit) {
        this.key = key;
        this.name = name;
        this.groupName = groupName == null || groupName.isEmpty() ? "default" : groupName;
        this.enabled = enabled;
        this.monthlyTokenLimit = monthlyTokenLimit < 0L ? 0L : monthlyTokenLimit;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getGroupName() {
        return groupName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMonthlyTokenLimit() {
        return monthlyTokenLimit;
    }
}
