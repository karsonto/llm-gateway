package org.icbca.gateway.inspect;

import org.icbca.gateway.auth.ApiKeyStore;
import org.icbca.gateway.auth.AuthInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Flattens user texts for CSV collect; optionally classifies last user turn asynchronously.
 * Never denies the request.
 */
public final class ClassificationInspector implements ChatRequestInspector {

    public static final String ATTR_USER_TEXT = "userText";
    public static final String ATTR_CATEGORY = "category";

    private static final String CATEGORY_PENDING = "pending";
    private static final String USER_SEP = "\n\n";

    private static final Logger log = LoggerFactory.getLogger(ClassificationInspector.class);

    private final UserPromptCsvCollector csvCollector;
    private final CategoryClient categoryClient;
    private final CategoryStatsRecorder categoryStatsRecorder;
    private final ExecutorService classifyExecutor;

    public ClassificationInspector(UserPromptCsvCollector csvCollector) {
        this(csvCollector, null, null, null);
    }

    public ClassificationInspector(UserPromptCsvCollector csvCollector,
                                   CategoryClient categoryClient,
                                   CategoryStatsRecorder categoryStatsRecorder,
                                   ExecutorService classifyExecutor) {
        this.csvCollector = csvCollector;
        this.categoryClient = categoryClient;
        this.categoryStatsRecorder = categoryStatsRecorder != null
                ? categoryStatsRecorder : NoopCategoryStatsRecorder.INSTANCE;
        this.classifyExecutor = classifyExecutor;
    }

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        String flattened = flattenUserTexts(ctx);
        String lastUser = extractLastUserText(ctx);
        ctx.getAttributes().put(ATTR_USER_TEXT, flattened);
        ctx.getAttributes().put(ATTR_CATEGORY, CATEGORY_PENDING);

        boolean ignored = isCollectIgnored(ctx);
        if (csvCollector != null && !flattened.isEmpty() && !ignored) {
            csvCollector.append(flattened);
        }

        if (!ignored && categoryClient != null && classifyExecutor != null && !lastUser.isEmpty()) {
            final String requestId = ctx.getRequestId();
            final String apiKey = stringAttr(ctx, AuthInspector.ATTR_API_KEY, ApiKeyStore.ANONYMOUS_KEY);
            final String apiKeyName = stringAttr(ctx, AuthInspector.ATTR_API_KEY_NAME, apiKey);
            final String text = lastUser;
            try {
                classifyExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        String category = categoryClient.classify(text, requestId);
                        categoryStatsRecorder.record(apiKey, apiKeyName, category);
                        log.info("requestId={} classified name={} category={}",
                                requestId, apiKeyName, category);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.warn("requestId={} classify queue full, skipped", requestId);
            }
        }

        return InspectionResult.allow();
    }

    /** Skip CSV/classify when request header {@code Classification: Ignore}. */
    private static boolean isCollectIgnored(ChatRequestContext ctx) {
        String v = ctx.getHeader("Classification");
        return v != null && "Ignore".equalsIgnoreCase(v.trim());
    }

    private static String stringAttr(ChatRequestContext ctx, String key, String fallback) {
        Object v = ctx.getAttributes().get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Last {@code role=user} content; otherwise completions {@code prompt}.
     */
    public static String extractLastUserText(ChatRequestContext ctx) {
        if (ctx == null) {
            return "";
        }
        List<Map<String, String>> messages = ctx.getMessages();
        if (messages != null && !messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, String> msg = messages.get(i);
                if (msg == null) {
                    continue;
                }
                String role = msg.get("role");
                if (role != null && "user".equalsIgnoreCase(role.trim())) {
                    String content = msg.get("content");
                    return content == null ? "" : content.trim();
                }
            }
        }
        String prompt = ctx.getPrompt();
        return prompt == null ? "" : prompt.trim();
    }

    /**
     * All {@code role=user} contents joined by {@code \\n\\n}; otherwise completions {@code prompt}.
     */
    public static String flattenUserTexts(ChatRequestContext ctx) {
        if (ctx == null) {
            return "";
        }
        List<Map<String, String>> messages = ctx.getMessages();
        if (messages != null && !messages.isEmpty()) {
            List<String> parts = new ArrayList<String>();
            for (Map<String, String> msg : messages) {
                if (msg == null) {
                    continue;
                }
                String role = msg.get("role");
                if (role == null || !"user".equalsIgnoreCase(role.trim())) {
                    continue;
                }
                String content = msg.get("content");
                if (content == null) {
                    continue;
                }
                String trimmed = content.trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                }
            }
            if (!parts.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        sb.append(USER_SEP);
                    }
                    sb.append(parts.get(i));
                }
                return sb.toString();
            }
        }
        String prompt = ctx.getPrompt();
        if (prompt == null) {
            return "";
        }
        return prompt.trim();
    }
}
