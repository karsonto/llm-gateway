package org.icbca.gateway.usage;

/**
 * Fixed millisecond bins for approximate p50/p99 from histogram counts.
 * Bin value is the upper bound of the bucket (inclusive).
 */
public final class LatencyHistBins {

    public static final String METRIC_TTFT = "ttft";
    public static final String METRIC_TPOT = "tpot";
    public static final String METRIC_ITL = "itl";

    /** Upper bounds in ms; last bucket catches everything above. */
    private static final long[] BINS = {
            5L, 10L, 20L, 50L, 100L, 200L, 500L,
            1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 50_000L,
            100_000L, 200_000L, 500_000L, Long.MAX_VALUE
    };

    private LatencyHistBins() {
    }

    public static long[] bins() {
        return BINS.clone();
    }

    public static long binFor(long valueMs) {
        long v = valueMs < 0 ? 0L : valueMs;
        for (int i = 0; i < BINS.length; i++) {
            if (v <= BINS[i]) {
                return BINS[i];
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * Approximate percentile from sorted (bin, count) pairs.
     * @param binsAscending bin upper bounds ascending
     * @param counts parallel counts
     */
    public static long percentile(long[] binsAscending, long[] counts, double p) {
        long total = 0L;
        for (int i = 0; i < counts.length; i++) {
            total += counts[i];
        }
        if (total <= 0) {
            return 0L;
        }
        long target = (long) Math.ceil(p * total);
        if (target < 1) {
            target = 1;
        }
        long cum = 0L;
        for (int i = 0; i < binsAscending.length; i++) {
            cum += counts[i];
            if (cum >= target) {
                if (binsAscending[i] == Long.MAX_VALUE) {
                    return i > 0 ? binsAscending[i - 1] : 0L;
                }
                return binsAscending[i];
            }
        }
        long last = binsAscending[binsAscending.length - 1];
        return last == Long.MAX_VALUE && binsAscending.length > 1
                ? binsAscending[binsAscending.length - 2] : last;
    }
}
