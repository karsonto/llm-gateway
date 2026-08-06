package org.icbca.gateway.usage;

/**
 * Records per-request latency aggregated by model and hour.
 */
public interface LatencyRecorder {

    void record(String model, LatencySample sample);
}
