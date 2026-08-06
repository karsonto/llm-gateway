package org.icbca.gateway.inspect;

/**
 * No-op category stats recorder.
 */
public final class NoopCategoryStatsRecorder implements CategoryStatsRecorder {

    public static final NoopCategoryStatsRecorder INSTANCE = new NoopCategoryStatsRecorder();

    private NoopCategoryStatsRecorder() {
    }

    @Override
    public void record(String apiKey, String name, String category) {
        // no-op
    }
}
