package org.icbca.gateway.auth;

/**
 * Registered API key metadata.
 */
public final class ApiKeyInfo {

    private static final String DEFAULT_DEPARTMENT = "FTD";

    private final String key;
    private final String name;
    private final String groupName;
    private final String department;
    private final boolean enabled;
    /** Absolute token count; {@code 0} means unlimited. */
    private final long monthlyTokenLimit;

    public ApiKeyInfo(String key, String name, boolean enabled) {
        this(key, name, "default", DEFAULT_DEPARTMENT, enabled, 0L);
    }

    public ApiKeyInfo(String key, String name, String groupName, boolean enabled) {
        this(key, name, groupName, DEFAULT_DEPARTMENT, enabled, 0L);
    }

    public ApiKeyInfo(String key, String name, String groupName, boolean enabled,
                      long monthlyTokenLimit) {
        this(key, name, groupName, DEFAULT_DEPARTMENT, enabled, monthlyTokenLimit);
    }

    public ApiKeyInfo(String key, String name, String groupName, String department,
                      boolean enabled, long monthlyTokenLimit) {
        this.key = key;
        this.name = name;
        this.groupName = groupName == null || groupName.isEmpty() ? "default" : groupName;
        this.department = department == null || department.trim().isEmpty()
                ? DEFAULT_DEPARTMENT : department.trim();
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

    public String getDepartment() {
        return department;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getMonthlyTokenLimit() {
        return monthlyTokenLimit;
    }
}
