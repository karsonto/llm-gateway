package org.icbca.gateway.inspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MVP inspector: logs conversation content and always allows.
 */
public final class LoggingInspector implements ChatRequestInspector {

    private static final Logger log = LoggerFactory.getLogger(LoggingInspector.class);

    @Override
    public InspectionResult inspect(ChatRequestContext ctx) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("requestId=").append(ctx.getRequestId())
                .append(" path=").append(ctx.getPath())
                .append(" model=").append(ctx.getModel())
                .append(" stream=").append(ctx.isStream());
        if (ctx.getPrompt() != null && !ctx.getPrompt().isEmpty()) {
            sb.append(" prompt=").append(ctx.getPrompt());
        }
        List<Map<String, String>> messages = ctx.getMessages();
        if (!messages.isEmpty()) {
            sb.append(" messages=[");
            for (int i = 0; i < messages.size(); i++) {
                Map<String, String> m = messages.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('{')
                        .append("role=").append(m.get("role"))
                        .append(", content=").append(m.get("content"))
                        .append('}');
            }
            sb.append(']');
        }
        log.info("chat request: {}", sb);
        return InspectionResult.allow();
    }
}
