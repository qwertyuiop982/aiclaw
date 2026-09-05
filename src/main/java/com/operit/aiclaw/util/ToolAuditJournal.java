package com.operit.aiclaw.util;

import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/** Append-only JSONL audit journal for model tool calls. Sensitive-looking values are masked. */
public final class ToolAuditJournal implements AutoCloseable {
    private final BufferedWriter writer;
    private final Object lock = new Object();

    public ToolAuditJournal(Path file) throws IOException {
        if (file == null) throw new IllegalArgumentException("journal file must not be null");
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public void toolCallStarted(String callId, String toolName, Map<String, Object> arguments) {
        write("tool_call_started", callId, toolName, arguments, null, null);
    }

    public void toolCallCompleted(String callId, String toolName, Map<String, Object> arguments,
                                  String status, long durationMs, String output) {
        JsonObject extra = new JsonObject();
        extra.addProperty("status", status == null ? "unknown" : status);
        extra.addProperty("duration_ms", durationMs);
        extra.addProperty("output_preview", preview(output, 2000));
        extra.addProperty("truncated", output != null && output.length() > 2000);
        write("tool_call_completed", callId, toolName, arguments, extra, output);
    }

    private void write(String event, String callId, String toolName, Map<String, Object> arguments,
                       JsonObject extra, String ignoredOutput) {
        JsonObject object = new JsonObject();
        object.addProperty("timestamp", Instant.now().toString());
        object.addProperty("event", event);
        object.addProperty("call_id", callId == null ? "" : callId);
        object.addProperty("tool", toolName == null ? "" : toolName);
        object.add("arguments", Json.gson().toJsonTree(mask(arguments)));
        if (extra != null) object.add("result", extra);
        synchronized (lock) {
            try {
                writer.write(object.toString());
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Auditing must never break the Agent's primary execution path.
            }
        }
    }

    private static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                out.put(key, isSensitive(key) ? "***REDACTED***" : mask(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object item : iterable) out.add(mask(item));
            return out;
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("key") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password")
                || normalized.contains("authorization") || normalized.contains("cookie");
    }

    private static String preview(String value, int max) {
        if (value == null) return "";
        String normalized = value.replace("\u001b", "");
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...";
    }

    @Override public void close() {
        synchronized (lock) {
            try { writer.close(); } catch (IOException ignored) { }
        }
    }
}