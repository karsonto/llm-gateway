package org.icbca.gateway.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP client for simple JSON intent classification.
 * <pre>
 * POST {"text":"...","request_id":"..."} -&gt; {"category":"..."}
 * </pre>
 */
public final class CategoryClient {

    public static final String CATEGORY_ERROR = "classify_error";

    private static final Logger log = LoggerFactory.getLogger(CategoryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final int timeoutMs;

    public CategoryClient(String url, int timeoutMs) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("classify url is required");
        }
        this.url = url.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2000;
    }

    /**
     * @return non-empty category; {@link #CATEGORY_ERROR} on failure
     */
    public String classify(String text, String requestId) {
        if (text == null || text.trim().isEmpty()) {
            return CATEGORY_ERROR;
        }
        HttpURLConnection conn = null;
        try {
            String body = MAPPER.createObjectNode()
                    .put("text", text)
                    .put("request_id", requestId == null ? "" : requestId)
                    .toString();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(payload.length);

            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = readFully(in);
            if (code < 200 || code >= 300) {
                log.warn("classify HTTP {} requestId={}: {}", code, requestId, truncate(resp));
                return CATEGORY_ERROR;
            }
            JsonNode root = MAPPER.readTree(resp);
            JsonNode cat = root.get("category");
            if (cat == null || cat.isNull() || !cat.isTextual()) {
                log.warn("classify missing category requestId={}", requestId);
                return CATEGORY_ERROR;
            }
            String value = cat.asText().trim();
            if (value.isEmpty()) {
                return CATEGORY_ERROR;
            }
            return value;
        } catch (Exception e) {
            log.warn("classify failed requestId={}: {}", requestId, e.getMessage());
            return CATEGORY_ERROR;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readFully(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        int n;
        while ((n = in.read(tmp)) >= 0) {
            buf.write(tmp, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
