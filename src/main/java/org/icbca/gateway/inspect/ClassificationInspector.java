package org.icbca.gateway.inspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Extracts the latest user question for future classification.
 * No real model yet: stores placeholders and always allows.
 */
public final class ClassificationInspector implements ChatRequestInspector {

    public static final String ATTR_USER_TEXT = "userText";
    public static final String ATTR_CATEGORY = "category";

    private static final String CATEGORY_PLACEHOLDER = "unclassified";
    private static final int LOG_TEXT_MAX = 200;

    private static final Logger CLASSIFY_LOG =
            LoggerFactory.getLogger("org.icbca.gateway.classification");

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        String userText = extractUserText(ctx);
        if (userText == null) {
            userText = "";
        }
        if (userText.isEmpty()) {
            CLASSIFY_LOG.warn("requestId={} classification: empty user text (path={})",
                    ctx.getRequestId(), ctx.getPath());
        }

        ctx.getAttributes().put(ATTR_USER_TEXT, userText);
        ctx.getAttributes().put(ATTR_CATEGORY, CATEGORY_PLACEHOLDER);

        CLASSIFY_LOG.info("requestId={} classification category={} userText={}",
                ctx.getRequestId(), CATEGORY_PLACEHOLDER, truncateForLog(userText));
        return InspectionResult.allow();
    }

    /**
     * Prefer last {@code role=user} message content; otherwise use completions {@code prompt}.
     */
    public static String extractUserText(ChatRequestContext ctx) {
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
                    return content == null ? "" : content;
                }
            }
        }
        String prompt = ctx.getPrompt();
        return prompt == null ? "" : prompt;
    }

    private static String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= LOG_TEXT_MAX) {
            return text;
        }
        return text.substring(0, LOG_TEXT_MAX) + "...";
    }
}
