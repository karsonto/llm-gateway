package org.icbca.gateway.usage;

import java.util.List;

/**
 * Records and queries API key token usage.
 * <p>
 * MVP: {@link InMemoryUsageRecorder}. Replace with a DB-backed implementation later
 * without changing call sites.
 */
public interface UsageRecorder {

    /**
     * Record one completed request. {@code usage} may be null (tokens stay 0, requestCount still increments).
     */
    void record(String apiKey, String apiKeyName, String model, TokenUsage usage);

    ApiKeyUsageStats getStats(String apiKey);

    List<ApiKeyUsageStats> getAllStats();
}
