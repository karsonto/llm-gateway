package org.icbca.gateway.inspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Appends flattened user prompts to a CSV with columns {@code text,label}.
 * Rotates the active file when it reaches 50MB.
 */
public final class UserPromptCsvCollector {

    private static final Logger log = LoggerFactory.getLogger(UserPromptCsvCollector.class);
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final String HEADER = "text,label";

    private final File activeFile;
    private final Object lock = new Object();

    public UserPromptCsvCollector(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("csv path is required");
        }
        this.activeFile = new File(path.trim());
        ensureParentAndHeader();
    }

    public void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            try {
                ensureParentAndHeader();
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(activeFile, true), StandardCharsets.UTF_8));
                try {
                    w.write(escapeCsv(text));
                    w.write(',');
                    w.newLine();
                } finally {
                    w.close();
                }
                if (activeFile.length() >= MAX_BYTES) {
                    rotate();
                }
            } catch (IOException e) {
                log.warn("failed to append user prompt csv: {}", e.getMessage());
            }
        }
    }

    private void ensureParentAndHeader() {
        try {
            File parent = activeFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("failed to create csv directory: {}", parent.getAbsolutePath());
            }
            if (!activeFile.exists() || activeFile.length() == 0L) {
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(activeFile, false), StandardCharsets.UTF_8));
                try {
                    w.write(HEADER);
                    w.newLine();
                } finally {
                    w.close();
                }
            }
        } catch (IOException e) {
            log.warn("failed to init user prompt csv: {}", e.getMessage());
        }
    }

    private void rotate() {
        String name = activeFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".csv";
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File parent = activeFile.getParentFile();
        File archived = new File(parent, base + "-" + stamp + ext);
        int n = 1;
        while (archived.exists()) {
            archived = new File(parent, base + "-" + stamp + "-" + n + ext);
            n++;
        }
        if (!activeFile.renameTo(archived)) {
            log.warn("failed to rotate csv to {}", archived.getAbsolutePath());
            return;
        }
        log.info("rotated user prompt csv to {}", archived.getAbsolutePath());
        ensureParentAndHeader();
    }

    static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == ',' || c == '\n' || c == '\r') {
                needQuotes = true;
                break;
            }
        }
        if (!needQuotes) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
