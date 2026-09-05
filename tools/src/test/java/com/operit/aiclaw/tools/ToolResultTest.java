package com.operit.aiclaw.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {
    @Test
    void serializesStructuredResult() {
        ToolResult result = new ToolResult(ToolStatus.SUCCESS, "done", "output", false, 12);
        String json = result.forModel();
        assertTrue(json.contains("success"));
        assertTrue(json.contains("duration_ms"));
        assertTrue(json.contains("output"));
    }
}