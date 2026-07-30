package org.icbca.gateway.inspect;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed conversation request context shared across {@link ChatRequestInspector}s.
 * {@link #attributes} may carry intermediate state (e.g. risk score) between inspectors.
 */
public final class ChatRequestContext {

    private final String requestId;
    private final String path;
    private final String model;
    private final List<Map<String, String>> messages;
    private final String prompt;
    private final boolean stream;
    private final JsonNode rawBody;
    private final Map<String, String> headers;
    private final Map<String, Object> attributes;

    public ChatRequestContext(String requestId, String path, String model,
                              List<Map<String, String>> messages, String prompt,
                              boolean stream, JsonNode rawBody, Map<String, String> headers) {
        this.requestId = requestId;
        this.path = path;
        this.model = model;
        this.messages = messages == null
                ? Collections.<Map<String, String>>emptyList()
                : Collections.unmodifiableList(messages);
        this.prompt = prompt;
        this.stream = stream;
        this.rawBody = rawBody;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(headers);
        this.attributes = new HashMap<String, Object>();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPath() {
        return path;
    }

    public String getModel() {
        return model;
    }

    public List<Map<String, String>> getMessages() {
        return messages;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isStream() {
        return stream;
    }

    public JsonNode getRawBody() {
        return rawBody;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }
}
