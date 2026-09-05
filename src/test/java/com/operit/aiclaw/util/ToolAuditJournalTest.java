package com.operit.aiclaw.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolAuditJournalTest {
    @Test
    void writesJsonlAndRedactsSensitiveArguments() throws Exception {
        Path dir = Files.createTempDirectory("aiclaw-audit-");
        Path file = dir.resolve("audit.jsonl");
        try (ToolAuditJournal journal = new ToolAuditJournal(file)) {
            journal.toolCallStarted("call-1", "terminal", Map.of("command", "echo ok", "api_key", "secret"));
            journal.toolCallCompleted("call-1", "terminal", Map.of("command", "echo ok", "token", "secret"),
                    "success", 12, "ok");
        }
        String content = Files.readString(file);
        assertEquals(2, content.lines().count());
        assertTrue(content.contains("call-1"));
        assertTrue(content.contains("REDACTED"));
        assertFalse(content.contains("secret"));
    }
}