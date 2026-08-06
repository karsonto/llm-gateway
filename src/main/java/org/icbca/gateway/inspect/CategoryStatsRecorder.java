package org.icbca.gateway.inspect;

/**
 * Records per-user intent category counts by calendar day.
 */
public interface CategoryStatsRecorder {

    void record(String apiKey, String name, String category);
}
