package org.icbca.gateway.usage;

/**
 * Token counts from an upstream OpenAI-compatible usage object.
 */
public final class TokenUsage {

    private final long promptTokens;
    private final long completionTokens;
    private final long totalTokens;

    public TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
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

    @Override
    public String toString() {
        return "TokenUsage{prompt=" + promptTokens
                + ", completion=" + completionTokens
                + ", total=" + totalTokens + '}';
    }
}
