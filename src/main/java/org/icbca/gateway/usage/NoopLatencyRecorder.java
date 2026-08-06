package org.icbca.gateway.usage;

/**
 * No-op latency recorder for in-memory / non-SQLite mode.
 */
public final class NoopLatencyRecorder implements LatencyRecorder {

    public static final NoopLatencyRecorder INSTANCE = new NoopLatencyRecorder();

    private NoopLatencyRecorder() {
    }

    @Override
    public void record(String model, LatencySample sample) {
        // no-op
    }
}
