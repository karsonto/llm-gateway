package org.icbca.gateway.usage;

/**
 * Aggregated usage for one API key.
 */
public final class ApiKeyUsageStats {

    private final String apiKey;
    private final String name;
    private final long requestCount;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public ApiKeyUsageStats(String apiKey, String name, long requestCount,
                            long promptTokens, long completionTokens, long totalTokens) {
        this.apiKey = apiKey;
        this.name = name;
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getName() {
        return name;
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
}
