package org.icbca.gateway.usage;

import java.util.Collections;
import java.util.List;

/**
 * One calendar day's usage for an API key, with optional per-model breakdown.
 */
public final class DailyUsageStats {

    private final String date;
    private final long requestCount;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;
    private final List<ModelUsageStats> byModel;

    public DailyUsageStats(String date, long requestCount, long promptTokens,
                           long completionTokens, long totalTokens,
                           List<ModelUsageStats> byModel) {
        this.date = date;
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.byModel = byModel == null
                ? Collections.<ModelUsageStats>emptyList()
                : Collections.unmodifiableList(byModel);
    }

    public String getDate() {
        return date;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public List<ModelUsageStats> getByModel() {
        return byModel;
    }
}
