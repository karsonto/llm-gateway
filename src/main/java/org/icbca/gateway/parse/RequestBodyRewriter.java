package org.icbca.gateway.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites outbound request JSON so streaming calls include usage in the SSE trail.
 */
public final class RequestBodyRewriter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyRewriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestBodyRewriter() {
    }

    /**
     * For {@code stream:true} bodies, ensure {@code stream_options.include_usage=true}.
     * Returns original content unchanged when not applicable or on parse failure
     * (caller must not release original until done; returned buffer may be a new buffer).
     *
     * @return rewritten content, or {@code null} if no rewrite (use original)
     */
    public static ByteBuf ensureStreamUsage(ByteBuf original) {
        if (original == null || !original.isReadable()) {
            return null;
        }
        String body = original.toString(CharsetUtil.UTF_8);
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode streamNode = root.get("stream");
            if (streamNode == null || !streamNode.isBoolean() || !streamNode.asBoolean()) {
                return null;
            }
            ObjectNode obj = (ObjectNode) root;
            ObjectNode streamOptions;
            JsonNode existing = obj.get("stream_options");
            if (existing != null && existing.isObject()) {
                streamOptions = (ObjectNode) existing;
            } else {
                streamOptions = MAPPER.createObjectNode();
                obj.set("stream_options", streamOptions);
            }
            if (streamOptions.has("include_usage")
                    && streamOptions.get("include_usage").isBoolean()
                    && streamOptions.get("include_usage").asBoolean()) {
                return null;
            }
            streamOptions.put("include_usage", true);
            byte[] bytes = MAPPER.writeValueAsBytes(obj);
            return Unpooled.wrappedBuffer(bytes);
        } catch (Exception e) {
            log.warn("failed to inject stream_options.include_usage: {}", e.getMessage());
            return null;
        }
    }
}
