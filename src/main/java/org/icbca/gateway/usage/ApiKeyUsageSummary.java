package org.icbca.gateway.usage;

import java.util.Collections;
import java.util.List;

/**
 * Summary for one API key: lifetime totals plus daily breakdown.
 */
public final class ApiKeyUsageSummary {

    private final String apiKey;
    private final String name;
    private final String groupName;
    private final UsageTotals total;
    private final List<DailyUsageStats> daily;

    public ApiKeyUsageSummary(String apiKey, String name, String groupName, UsageTotals total,
                              List<DailyUsageStats> daily) {
        this.apiKey = apiKey;
        this.name = name;
        this.groupName = groupName == null || groupName.isEmpty() ? "default" : groupName;
        this.total = total == null ? UsageTotals.zero() : total;
        this.daily = daily == null
                ? Collections.<DailyUsageStats>emptyList()
                : Collections.unmodifiableList(daily);
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getName() {
        return name;
    }

    public String getGroupName() {
        return groupName;
    }

    public UsageTotals getTotal() {
        return total;
    }

    public List<DailyUsageStats> getDaily() {
        return daily;
    }
}
