package com.operit.aiclaw.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThinkingConfigTest {

    @Test
    void modeFourUsesThinkingTypeAndCanRemoveIt() {
        ThinkingConfig thinking = new ThinkingConfig();

        thinking.setModeValue(4, "adaptive");

        assertEquals("enabled", thinking.getModeValue(4));
        assertEquals("enabled", thinking.extraBody().get("thinking.type"));
        assertFalse(thinking.extraBody().containsKey("thinking.is always enabled"));

        thinking.removeMode(4);

        assertNull(thinking.getModeValue(4));
        assertFalse(thinking.extraBody().containsKey("thinking.type"));
    }

    @Test
    void fixedModesIgnoreCallerSuppliedValuesAndNumericShorthands() {
        ThinkingConfig thinking = new ThinkingConfig();

        thinking.setModeValue(4, "adaptive");
        thinking.setModeValue(5, "lite");
        assertEquals("enabled", thinking.getModeValue(4));
        assertEquals("pro", thinking.getModeValue(5));

        thinking.removeMode(4).removeMode(5).addMode("4,5", "disabled", "0");
        assertEquals("enabled", thinking.getModeValue(4));
        assertEquals("pro", thinking.getModeValue(5));
    }

    @Test
    void geminiBudgetHasExactlyOneGenerationConfigLayer() {
        ThinkingConfig thinking = new ThinkingConfig().setModeValue(3, "32768");
        JsonObject body = new JsonObject();

        thinking.apply(body, RequestStyle.GEMINI_GENERAL);

        JsonObject generationConfig = body.getAsJsonObject("generationConfig");
        assertFalse(generationConfig.has("generationConfig"));
        assertEquals(32768L, generationConfig.getAsJsonObject("thinkingConfig")
                .get("thinkingBudget").getAsLong());
    }

    @Test
    void modeFiveOnlyUsesReasoningModeAndDoesNotCreateLegacyThinkingBudget() {
        ThinkingConfig thinking = new ThinkingConfig().setModeValue(5, "pro");

        assertEquals("pro", thinking.getModeValue(5));
        assertFalse(thinking.topLevel().containsKey("thinking_budget"));
        assertFalse(thinking.extraBody().containsKey("thinking_budget"));
    }
}