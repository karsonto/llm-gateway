package org.icbca.gateway.usage;

import java.util.List;

/**
 * Records and queries API key token usage.
 * <p>
 * MVP: {@link InMemoryUsageRecorder}. Replace with a DB-backed implementation later
 * without changing call sites.
 * <p>
 * Stats are aggregated by {@code apiKey + date + model}.
 */
public interface UsageRecorder {

    /**
     * Record one completed request under today's date and the given model.
     * {@code usage} may be null (tokens stay 0, requestCount still increments).
     */
    void record(String apiKey, String apiKeyName, String model, TokenUsage usage);

    /**
     * All date/model rows for one API key (may be empty).
     */
    List<ApiKeyUsageStats> getStats(String apiKey);

    /**
     * All date/model rows across all API keys.
     */
    List<ApiKeyUsageStats> getAllStats();
}
