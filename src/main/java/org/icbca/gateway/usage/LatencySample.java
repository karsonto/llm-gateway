package org.icbca.gateway.usage;

/**
 * Per-request latency sample. Negative values mean unavailable.
 */
public final class LatencySample {

    private final long latencyMs;
    private final long ttftMs;
    private final long tpotMs;
    private final long itlMs;
    private final long outputTpsMilli;
    private final long promptTokens;
    private final long completionTokens;

    public LatencySample(long latencyMs, long ttftMs, long tpotMs, long itlMs,
                         long outputTpsMilli, long promptTokens, long completionTokens) {
        this.latencyMs = latencyMs;
        this.ttftMs = ttftMs;
        this.tpotMs = tpotMs;
        this.itlMs = itlMs;
        this.outputTpsMilli = outputTpsMilli;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public long getTtftMs() {
        return ttftMs;
    }

    public long getTpotMs() {
        return tpotMs;
    }

    public long getItlMs() {
        return itlMs;
    }

    /** Tokens/sec * 1000 for integer aggregation. */
    public long getOutputTpsMilli() {
        return outputTpsMilli;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }
}
