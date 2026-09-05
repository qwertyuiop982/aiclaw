package com.operit.aiclaw.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the model-facing conversation bounded while preserving complete tool-call pairs. */
public final class ContextManager {
    private final int maxChars;
    private final int keepMessages;

    public ContextManager() {
        this(120_000, 24);
    }

    public ContextManager(int maxChars, int keepMessages) {
        this.maxChars = Math.max(100, maxChars);
        this.keepMessages = Math.max(1, keepMessages);
    }

    /** Compact old completed turns. The original list is modified in place. */
    public void compact(List<Map<String, Object>> messages) {
        if (messages == null || messages.size() <= keepMessages + 1) return;
        if (estimatedChars(messages) <= maxChars) return;

        int systemEnd = 0;
        while (systemEnd < messages.size()
                && "system".equals(String.valueOf(messages.get(systemEnd).get("role")))) systemEnd++;
        int keepStart = Math.max(systemEnd, messages.size() - keepMessages);
        // Never start the retained window in the middle of a tool-call group.
        while (keepStart > systemEnd && isPartOfToolGroup(messages.get(keepStart))) {
            keepStart--;
        }
        if (keepStart <= systemEnd) return;

        List<Map<String, Object>> old = new ArrayList<>(messages.subList(systemEnd, keepStart));
        String summaryText = summarize(old);
        messages.subList(systemEnd, keepStart).clear();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", "system");
        summary.put("content", summaryText);
        messages.add(systemEnd, summary);
    }

    public int estimatedChars(List<Map<String, Object>> messages) {
        int total = 0;
        if (messages != null) for (Map<String, Object> message : messages) total += String.valueOf(message).length();
        return total;
    }

    private static boolean isPartOfToolGroup(Map<String, Object> message) {
        if (message == null) return false;
        if ("tool".equals(String.valueOf(message.get("role")))) return true;
        return "assistant".equals(String.valueOf(message.get("role")))
                && message.get("tool_calls") instanceof List<?> calls
                && !calls.isEmpty();
    }

    private static String summarize(List<Map<String, Object>> messages) {
        StringBuilder out = new StringBuilder("[conversation summary: earlier messages compacted]\n");
        int index = 1;
        for (Map<String, Object> message : messages) {
            String role = String.valueOf(message.getOrDefault("role", "unknown"));
            String content = String.valueOf(message.getOrDefault("content", ""))
                    .replaceAll("\\s+", " ").trim();
            if (content.length() > 300) content = content.substring(0, 300) + "...";
            out.append(index++).append(". ").append(role).append(": ").append(content).append('\n');
        }
        return out.toString();
    }
}