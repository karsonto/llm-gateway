package org.icbca.gateway.parse;

/**
 * Stream token timing snapshot collected by {@link SseTokenParser}.
 * Times are System.nanoTime() values; {@code < 0} means unavailable.
 */
public final class TokenTimingStats {

    private final long firstTokenNanos;
    private final long lastTokenNanos;
    private final long intervalSumNanos;
    private final long intervalCount;
    private final long emittedChunks;

    public TokenTimingStats(long firstTokenNanos, long lastTokenNanos,
                            long intervalSumNanos, long intervalCount, long emittedChunks) {
        this.firstTokenNanos = firstTokenNanos;
        this.lastTokenNanos = lastTokenNanos;
        this.intervalSumNanos = intervalSumNanos;
        this.intervalCount = intervalCount;
        this.emittedChunks = emittedChunks;
    }

    public static TokenTimingStats empty() {
        return new TokenTimingStats(-1L, -1L, 0L, 0L, 0L);
    }

    public long getFirstTokenNanos() {
        return firstTokenNanos;
    }

    public long getLastTokenNanos() {
        return lastTokenNanos;
    }

    public long getIntervalSumNanos() {
        return intervalSumNanos;
    }

    public long getIntervalCount() {
        return intervalCount;
    }

    public long getEmittedChunks() {
        return emittedChunks;
    }

    public boolean hasFirstToken() {
        return firstTokenNanos >= 0;
    }
}
