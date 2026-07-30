package org.icbca.gateway.usage;

/**
 * Token/request counters without key/date/model identity.
 */
public final class UsageTotals {

    private final long requestCount;
    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public UsageTotals(long requestCount, long promptTokens, long completionTokens, long totalTokens) {
        this.requestCount = requestCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public static UsageTotals zero() {
        return new UsageTotals(0, 0, 0, 0);
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
