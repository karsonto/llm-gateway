package org.icbca.gateway.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;
import org.icbca.gateway.inspect.ChatRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses an inbound HTTP request into {@link ChatRequestContext}.
 */
public final class RequestBodyParser {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBodyParser() {
    }

    public static ChatRequestContext parse(FullHttpRequest request, String path) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String model = null;
        String prompt = null;
        boolean stream = false;
        JsonNode rawBody = null;
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();

        ByteBuf content = request.content();
        if (content != null && content.isReadable()) {
            String body = content.toString(CharsetUtil.UTF_8);
            if (body != null && !body.isEmpty()) {
                try {
                    rawBody = MAPPER.readTree(body);
                    if (rawBody != null && rawBody.isObject()) {
                        JsonNode modelNode = rawBody.get("model");
                        if (modelNode != null && !modelNode.isNull()) {
                            model = modelNode.asText();
                        }
                        JsonNode streamNode = rawBody.get("stream");
                        if (streamNode != null && streamNode.isBoolean()) {
                            stream = streamNode.asBoolean();
                        }
                        JsonNode promptNode = rawBody.get("prompt");
                        if (promptNode != null && !promptNode.isNull()) {
                            prompt = promptNode.isTextual() ? promptNode.asText() : promptNode.toString();
                        }
                        JsonNode messagesNode = rawBody.get("messages");
                        if (messagesNode != null && messagesNode.isArray()) {
                            for (JsonNode msg : messagesNode) {
                                Map<String, String> entry = new HashMap<String, String>();
                                JsonNode role = msg.get("role");
                                JsonNode msgContent = msg.get("content");
                                entry.put("role", role == null || role.isNull() ? "" : role.asText());
                                entry.put("content", extractContent(msgContent));
                                messages.add(entry);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("failed to parse request body, requestId={}: {}", requestId, e.getMessage());
                }
            }
        }
        return new ChatRequestContext(requestId, path, model, messages, prompt, stream, rawBody);
    }

    private static String extractContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        return contentNode.toString();
    }
}
