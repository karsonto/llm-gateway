package org.icbca.gateway.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local usage aggregation by apiKey + date + model. Data is lost on restart.
 * Swap for a DB/Redis implementation via {@link UsageRecorder} when needed.
 */
public final class InMemoryUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUsageRecorder.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String UNKNOWN_MODEL = "unknown";

    private final ConcurrentHashMap<String, Counters> byDimension = new ConcurrentHashMap<String, Counters>();
    private final ZoneId zoneId;

    public InMemoryUsageRecorder() {
        this(ZoneId.systemDefault());
    }

    public InMemoryUsageRecorder(ZoneId zoneId) {
        this.zoneId = zoneId != null ? zoneId : ZoneId.systemDefault();
    }

    @Override
    public void record(String apiKey, String apiKeyName, String model, TokenUsage usage) {
        String key = normalizeApiKey(apiKey);
        String name = apiKeyName == null || apiKeyName.isEmpty() ? key : apiKeyName;
        String modelName = normalizeModel(model);
        String date = LocalDate.now(zoneId).format(DAY);
        String dimensionKey = dimensionKey(key, date, modelName);

        Counters c = byDimension.get(dimensionKey);
        if (c == null) {
            Counters created = new Counters(key, name, date, modelName);
            Counters existing = byDimension.putIfAbsent(dimensionKey, created);
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

        log.info("usage recorded apiKey={} date={} model={} prompt={} completion={} total={} requests={}",
                key, date, modelName, prompt, completion, total, c.requestCount.sum());
    }

    @Override
    public List<ApiKeyUsageStats> getStats(String apiKey) {
        String key = normalizeApiKey(apiKey);
        List<ApiKeyUsageStats> list = new ArrayList<ApiKeyUsageStats>();
        for (Counters c : byDimension.values()) {
            if (key.equals(c.apiKey)) {
                list.add(c.snapshot());
            }
        }
        sort(list);
        return list;
    }

    @Override
    public List<ApiKeyUsageStats> getAllStats() {
        List<ApiKeyUsageStats> list = new ArrayList<ApiKeyUsageStats>();
        for (Counters c : byDimension.values()) {
            list.add(c.snapshot());
        }
        sort(list);
        return list;
    }

    private static void sort(List<ApiKeyUsageStats> list) {
        Collections.sort(list, new Comparator<ApiKeyUsageStats>() {
            @Override
            public int compare(ApiKeyUsageStats a, ApiKeyUsageStats b) {
                int c = a.getApiKey().compareTo(b.getApiKey());
                if (c != 0) {
                    return c;
                }
                c = b.getDate().compareTo(a.getDate()); // newer date first
                if (c != 0) {
                    return c;
                }
                return a.getModel().compareTo(b.getModel());
            }
        });
    }

    static String dimensionKey(String apiKey, String date, String model) {
        return apiKey + '\0' + date + '\0' + model;
    }

    private static String normalizeApiKey(String apiKey) {
        return apiKey == null || apiKey.isEmpty() ? "anonymous" : apiKey;
    }

    private static String normalizeModel(String model) {
        return model == null || model.isEmpty() ? UNKNOWN_MODEL : model;
    }

    private static final class Counters {
        final String apiKey;
        volatile String name;
        final String date;
        final String model;
        final LongAdder requestCount = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final LongAdder totalTokens = new LongAdder();

        Counters(String apiKey, String name, String date, String model) {
            this.apiKey = apiKey;
            this.name = name;
            this.date = date;
            this.model = model;
        }

        ApiKeyUsageStats snapshot() {
            return new ApiKeyUsageStats(
                    apiKey,
                    name,
                    date,
                    model,
                    requestCount.sum(),
                    promptTokens.sum(),
                    completionTokens.sum(),
                    totalTokens.sum());
        }
    }
}
