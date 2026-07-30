package org.icbca.gateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local usage aggregation. Data is lost on restart.
 * Swap for a DB/Redis implementation via {@link UsageRecorder} when needed.
 */
public final class InMemoryUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUsageRecorder.class);

    private final ConcurrentHashMap<String, Counters> byKey = new ConcurrentHashMap<String, Counters>();

    @Override
    public void record(String apiKey, String apiKeyName, String model, TokenUsage usage) {
        String key = apiKey == null || apiKey.isEmpty() ? "anonymous" : apiKey;
        String name = apiKeyName == null || apiKeyName.isEmpty() ? key : apiKeyName;
        Counters c = byKey.get(key);
        if (c == null) {
            Counters created = new Counters(key, name);
            Counters existing = byKey.putIfAbsent(key, created);
            c = existing != null ? existing : created;
        } else if (apiKeyName != null && !apiKeyName.isEmpty()) {
            c.name = apiKeyName;
        }

        c.requestCount.increment();
        long prompt = 0;
        long completion = 0;
        long total = 0;
        if (usage != null) {
            prompt = usage.getPromptTokens();
            completion = usage.getCompletionTokens();
            total = usage.getTotalTokens();
            c.promptTokens.add(prompt);
            c.completionTokens.add(completion);
            c.totalTokens.add(total);
        }

        log.info("usage recorded apiKey={} model={} prompt={} completion={} total={} requests={}",
                key, model, prompt, completion, total, c.requestCount.sum());
    }

    @Override
    public ApiKeyUsageStats getStats(String apiKey) {
        String key = apiKey == null || apiKey.isEmpty() ? "anonymous" : apiKey;
        Counters c = byKey.get(key);
        if (c == null) {
            return new ApiKeyUsageStats(key, key, 0, 0, 0, 0);
        }
        return c.snapshot();
    }

    @Override
    public List<ApiKeyUsageStats> getAllStats() {
        List<ApiKeyUsageStats> list = new ArrayList<ApiKeyUsageStats>();
        for (Counters c : byKey.values()) {
            list.add(c.snapshot());
        }
        return list;
    }

    private static final class Counters {
        final String apiKey;
        volatile String name;
        final LongAdder requestCount = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final LongAdder totalTokens = new LongAdder();

        Counters(String apiKey, String name) {
            this.apiKey = apiKey;
            this.name = name;
        }

        ApiKeyUsageStats snapshot() {
            return new ApiKeyUsageStats(
                    apiKey,
                    name,
                    requestCount.sum(),
                    promptTokens.sum(),
                    completionTokens.sum(),
                    totalTokens.sum());
        }
    }
}
