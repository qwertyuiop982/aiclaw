package com.operit.aiclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {
    @Test
    void compactsOldMessagesButKeepsSystemAndRecentMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "system prompt"));
        for (int i = 0; i < 8; i++) messages.add(message("user", "old message " + i + " xxxxxxxxxxxxxxxxxxxx"));
        messages.add(message("user", "recent"));

        ContextManager manager = new ContextManager(100, 4);
        manager.compact(messages);

        assertEquals("system", messages.get(0).get("role"));
        assertTrue(messages.stream().anyMatch(m -> String.valueOf(m.get("content")).contains("summary")));
        assertTrue(messages.stream().anyMatch(m -> "recent".equals(m.get("content"))));
    }

    @Test
    void doesNotSplitToolCallGroup() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "system"));
        messages.add(message("user", "old"));
        Map<String, Object> assistant = message("assistant", "");
        assistant.put("tool_calls", List.of(Map.of("id", "call-1")));
        messages.add(assistant);
        messages.add(Map.of("role", "tool", "tool_call_id", "call-1", "content", "result"));
        messages.add(message("user", "new"));

        new ContextManager(1, 1).compact(messages);

        boolean hasAssistantCall = messages.stream().anyMatch(m -> m.containsKey("tool_calls"));
        boolean hasToolResult = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));
        assertEquals(hasAssistantCall, hasToolResult);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}