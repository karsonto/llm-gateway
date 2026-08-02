package org.icbca.gateway.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flattens all role=user texts for future classification; optionally appends to CSV.
 * No real model yet: stores placeholders and always allows.
 */
public final class ClassificationInspector implements ChatRequestInspector {

    public static final String ATTR_USER_TEXT = "userText";
    public static final String ATTR_CATEGORY = "category";

    private static final String CATEGORY_PLACEHOLDER = "unclassified";
    private static final String USER_SEP = "\n\n";

    private final UserPromptCsvCollector csvCollector;

    public ClassificationInspector(UserPromptCsvCollector csvCollector) {
        this.csvCollector = csvCollector;
    }

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        String userText = flattenUserTexts(ctx);
        ctx.getAttributes().put(ATTR_USER_TEXT, userText);
        ctx.getAttributes().put(ATTR_CATEGORY, CATEGORY_PLACEHOLDER);

        if (csvCollector != null && !userText.isEmpty()) {
            csvCollector.append(userText);
        }
        return InspectionResult.allow();
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
