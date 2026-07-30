package org.icbca.gateway.usage;

/**
 * Usage for one model within a day (or overall when nested under daily).
 */
public final class ModelUsageStats {

    private final String model;
    private final long requestCount;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public ModelUsageStats(String model, long requestCount, long promptTokens,
                           long completionTokens, long totalTokens) {
        this.model = model;
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public String getModel() {
        return model;
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
