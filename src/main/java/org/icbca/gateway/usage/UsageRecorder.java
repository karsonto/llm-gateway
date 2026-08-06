package org.icbca.gateway.usage;

import java.util.List;

/**
 * Records and queries API key token usage.
 * <p>
 * MVP: {@link InMemoryUsageRecorder}. Replace with a DB-backed implementation later
 * without changing call sites.
 * <p>
 * Stats are stored by {@code apiKey + date + model}; summaries aggregate on read.
 */
public interface UsageRecorder {

    /**
     * Record one completed request under today's date and the given model.
     * {@code usage} may be null (tokens stay 0, requestCount still increments).
     */
    void record(String apiKey, String apiKeyName, String model, TokenUsage usage);

    /**
     * Fine-grained date/model rows for one API key (may be empty).
     */
    List<ApiKeyUsageStats> getStats(String apiKey);

    /**
     * Fine-grained date/model rows across all API keys.
     */
    List<ApiKeyUsageStats> getAllStats();

    /**
     * Total + daily (+ by_model) summary for one API key.
     *
     * @param dateFilter optional {@code yyyy-MM-dd}; null/empty = all dates
     */
    ApiKeyUsageSummary getSummary(String apiKey, String dateFilter);

    /**
     * Summaries for all API keys that have recorded usage.
     *
     * @param dateFilter optional {@code yyyy-MM-dd}; null/empty = all dates
     */
    List<ApiKeyUsageSummary> getAllSummaries(String dateFilter);

    /**
     * Sum of {@code total_tokens} for one API key in a calendar month.
     *
     * @param apiKey    API key (null/empty treated as anonymous)
     * @param yearMonth {@code yyyy-MM}
     */
    long sumTotalTokensForMonth(String apiKey, String yearMonth);
}
