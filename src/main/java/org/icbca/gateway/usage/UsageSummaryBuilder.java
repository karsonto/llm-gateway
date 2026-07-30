package org.icbca.gateway.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link ApiKeyUsageSummary} from fine-grained {@link ApiKeyUsageStats} rows.
 */
public final class UsageSummaryBuilder {

    private UsageSummaryBuilder() {
    }

    /**
     * @param rows       date/model rows for a single api key (or mixed keys — grouped by key)
     * @param dateFilter optional {@code yyyy-MM-dd}; null means all dates
     */
    public static List<ApiKeyUsageSummary> buildAll(List<ApiKeyUsageStats> rows, String dateFilter) {
        Map<String, List<ApiKeyUsageStats>> byKey = new LinkedHashMap<String, List<ApiKeyUsageStats>>();
        if (rows != null) {
            for (ApiKeyUsageStats row : rows) {
                if (dateFilter != null && !dateFilter.isEmpty() && !dateFilter.equals(row.getDate())) {
                    continue;
                }
                List<ApiKeyUsageStats> list = byKey.get(row.getApiKey());
                if (list == null) {
                    list = new ArrayList<ApiKeyUsageStats>();
                    byKey.put(row.getApiKey(), list);
                }
                list.add(row);
            }
        }
        List<ApiKeyUsageSummary> result = new ArrayList<ApiKeyUsageSummary>();
        for (Map.Entry<String, List<ApiKeyUsageStats>> e : byKey.entrySet()) {
            result.add(buildOne(e.getValue(), "default"));
        }
        return result;
    }

    public static ApiKeyUsageSummary buildOne(List<ApiKeyUsageStats> rows) {
        return buildOne(rows, "default");
    }

    /**
     * Build summary for one key from its rows.
     */
    public static ApiKeyUsageSummary buildOne(List<ApiKeyUsageStats> rows, String groupName) {
        if (rows == null || rows.isEmpty()) {
            return empty("anonymous", "anonymous",
                    groupName == null || groupName.isEmpty() ? "anonymous" : groupName);
        }
        String apiKey = rows.get(0).getApiKey();
        String name = rows.get(0).getName();

        Map<String, List<ApiKeyUsageStats>> byDate = new LinkedHashMap<String, List<ApiKeyUsageStats>>();
        long totalReq = 0;
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalTokens = 0;

        for (ApiKeyUsageStats row : rows) {
            if (row.getName() != null && !row.getName().isEmpty()) {
                name = row.getName();
            }
            totalReq += row.getRequestCount();
            totalPrompt += row.getPromptTokens();
            totalCompletion += row.getCompletionTokens();
            totalTokens += row.getTotalTokens();

            List<ApiKeyUsageStats> dayRows = byDate.get(row.getDate());
            if (dayRows == null) {
                dayRows = new ArrayList<ApiKeyUsageStats>();
                byDate.put(row.getDate(), dayRows);
            }
            dayRows.add(row);
        }

        List<DailyUsageStats> daily = new ArrayList<DailyUsageStats>();
        for (Map.Entry<String, List<ApiKeyUsageStats>> e : byDate.entrySet()) {
            daily.add(buildDaily(e.getKey(), e.getValue()));
        }
        Collections.sort(daily, new Comparator<DailyUsageStats>() {
            @Override
            public int compare(DailyUsageStats a, DailyUsageStats b) {
                return b.getDate().compareTo(a.getDate());
            }
        });

        return new ApiKeyUsageSummary(
                apiKey,
                name,
                groupName,
                new UsageTotals(totalReq, totalPrompt, totalCompletion, totalTokens),
                daily);
    }

    public static ApiKeyUsageSummary empty(String apiKey, String name) {
        return empty(apiKey, name, "default");
    }

    public static ApiKeyUsageSummary empty(String apiKey, String name, String groupName) {
        return new ApiKeyUsageSummary(
                apiKey,
                name == null || name.isEmpty() ? apiKey : name,
                groupName,
                UsageTotals.zero(),
                Collections.<DailyUsageStats>emptyList());
    }

    private static DailyUsageStats buildDaily(String date, List<ApiKeyUsageStats> rows) {
        List<ModelUsageStats> byModel = new ArrayList<ModelUsageStats>();
        long req = 0;
        long prompt = 0;
        long completion = 0;
        long total = 0;
        for (ApiKeyUsageStats row : rows) {
            req += row.getRequestCount();
            prompt += row.getPromptTokens();
            completion += row.getCompletionTokens();
            total += row.getTotalTokens();
            byModel.add(new ModelUsageStats(
                    row.getModel(),
                    row.getRequestCount(),
                    row.getPromptTokens(),
                    row.getCompletionTokens(),
                    row.getTotalTokens()));
        }
        Collections.sort(byModel, new Comparator<ModelUsageStats>() {
            @Override
            public int compare(ModelUsageStats a, ModelUsageStats b) {
                return a.getModel().compareTo(b.getModel());
            }
        });
        return new DailyUsageStats(date, req, prompt, completion, total, byModel);
    }
}
