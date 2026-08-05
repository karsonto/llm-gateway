package org.icbca.gateway.usage;

/**
 * Records per-request latency aggregated by model and hour.
 */
public interface LatencyRecorder {

    /**
     * @param ttftMs time to first upstream byte in ms; {@code < 0} if unavailable
     */
    void record(String model, long latencyMs, long ttftMs);
}
