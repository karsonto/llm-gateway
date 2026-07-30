package org.icbca.gateway.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.icbca.gateway.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incremental SSE parser for vLLM / OpenAI-compatible streaming responses.
 * Safe across UTF-8 chunk boundaries.
 */
public final class SseTokenParser {

    private static final Logger log = LoggerFactory.getLogger(SseTokenParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String requestId;
    private final StringBuilder lineBuffer = new StringBuilder(256);
    private final StringBuilder assistantReply = new StringBuilder(1024);
    private ByteBuf carry;
    private boolean done;
    private TokenUsage usage;

    public SseTokenParser(String requestId) {
        this.requestId = requestId;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    /**
     * Decode complete UTF-8 characters; hold incomplete trailing bytes for the next feed.
     */
    public void feedUtf8(ByteBuf content) {
        if (done || content == null || !content.isReadable()) {
            return;
        }
        ByteBuf combined = Unpooled.buffer(
                (carry == null ? 0 : carry.readableBytes()) + content.readableBytes());
        try {
            if (carry != null && carry.isReadable()) {
                combined.writeBytes(carry);
            }
            combined.writeBytes(content, content.readerIndex(), content.readableBytes());
            releaseCarry();

            int readable = combined.readableBytes();
            int safeEnd = findUtf8SafeEnd(combined, readable);
            if (safeEnd < readable) {
                carry = Unpooled.buffer(readable - safeEnd);
                carry.writeBytes(combined, combined.readerIndex() + safeEnd, readable - safeEnd);
                combined.writerIndex(combined.readerIndex() + safeEnd);
            }
            String chunk = combined.toString(CharsetUtil.UTF_8);
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (c == '\n') {
                    processLine(lineBuffer.toString());
                    lineBuffer.setLength(0);
                } else if (c != '\r') {
                    lineBuffer.append(c);
                }
            }
        } finally {
            combined.release();
        }
    }

    public void finishNonStream(ByteBuf fullBody) {
        if (fullBody == null || !fullBody.isReadable()) {
            return;
        }
        try {
            String json = fullBody.toString(CharsetUtil.UTF_8);
            JsonNode root = MAPPER.readTree(json);
            String text = extractMessageContent(root);
            if (text != null && !text.isEmpty()) {
                log.info("requestId={} non-stream reply: {}", requestId, text);
            }
            TokenUsage parsed = parseUsage(root.get("usage"));
            if (parsed != null) {
                usage = parsed;
                log.info("requestId={} non-stream usage: {}", requestId, usage);
            }
        } catch (Exception e) {
            log.warn("requestId={} failed to parse non-stream body: {}", requestId, e.getMessage());
        }
    }

    public void onComplete() {
        if (!done && lineBuffer.length() > 0) {
            processLine(lineBuffer.toString());
            lineBuffer.setLength(0);
        }
        if (!done && assistantReply.length() > 0) {
            log.info("requestId={} stream complete reply: {}", requestId, assistantReply);
        }
        releaseCarry();
    }

    public void release() {
        releaseCarry();
    }

    private void releaseCarry() {
        if (carry != null) {
            carry.release();
            carry = null;
        }
    }

    private void processLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty()) {
            return;
        }
        if ("[DONE]".equals(data)) {
            done = true;
            log.info("requestId={} stream [DONE], full reply: {}", requestId, assistantReply);
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(data);
            String token = extractDelta(root);
            if (token != null && !token.isEmpty()) {
                assistantReply.append(token);
                log.debug("requestId={} token={}", requestId, token);
            }
            TokenUsage parsed = parseUsage(root.get("usage"));
            if (parsed != null) {
                usage = parsed;
                log.debug("requestId={} stream usage: {}", requestId, usage);
            }
        } catch (Exception e) {
            log.warn("requestId={} SSE data parse failed: {}", requestId, e.getMessage());
        }
    }

    static TokenUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull() || !usageNode.isObject()) {
            return null;
        }
        long prompt = readLong(usageNode, "prompt_tokens");
        long completion = readLong(usageNode, "completion_tokens");
        long total = readLong(usageNode, "total_tokens");
        if (total == 0 && (prompt > 0 || completion > 0)) {
            total = prompt + completion;
        }
        return new TokenUsage(prompt, completion, total);
    }

    private static long readLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return 0L;
        }
        return v.asLong();
    }

    private static String extractDelta(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            return null;
        }
        JsonNode first = choices.get(0);
        JsonNode delta = first.get("delta");
        if (delta != null) {
            JsonNode content = delta.get("content");
            if (content != null && content.isTextual()) {
                return content.asText();
            }
        }
        JsonNode text = first.get("text");
        if (text != null && text.isTextual()) {
            return text.asText();
        }
        return null;
    }

    private static String extractMessageContent(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            return null;
        }
        JsonNode first = choices.get(0);
        JsonNode message = first.get("message");
        if (message != null) {
            JsonNode content = message.get("content");
            if (content != null && content.isTextual()) {
                return content.asText();
            }
        }
        JsonNode text = first.get("text");
        if (text != null && text.isTextual()) {
            return text.asText();
        }
        return null;
    }

    private static int findUtf8SafeEnd(ByteBuf buf, int length) {
        if (length == 0) {
            return 0;
        }
        int i = length - 1;
        int reader = buf.readerIndex();
        int cont = 0;
        while (i >= 0 && cont < 3) {
            int b = buf.getByte(reader + i) & 0xFF;
            if ((b & 0xC0) != 0x80) {
                break;
            }
            cont++;
            i--;
        }
        if (i < 0) {
            return 0;
        }
        int lead = buf.getByte(reader + i) & 0xFF;
        int expected;
        if ((lead & 0x80) == 0) {
            expected = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            return length;
        }
        int available = length - i;
        if (available < expected) {
            return i;
        }
        return length;
    }
}
