package com.operit.aiclaw.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * LLM client abstraction. Implementations turn the call into a concrete HTTP request
 * and normalize the response into a {@link ChatResponse}.
 */
public interface LlmClient {

    /**
     * Plain chat call (no tools).
     *
     * @param model    the model ID
     * @param messages the conversation so far; each entry is a Map of role/content
     * @param options  optional parameters (temperature, max_tokens, ...)
     */
    ChatResponse chat(String model, List<Map<String, Object>> messages, Map<String, Object> options);

    /**
     * Tool-calling chat.
     *
     * @param tools tool definitions in OpenAI tool schema format
     */
    ChatResponse chatWithTools(String model,
                               List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools,
                               Map<String, Object> options);

    /**
     * Normalized chat response. {@code reasoning} is the optional reasoning/thinking text
     * returned by providers that support it (OpenAI o-series, Claude extended thinking, etc.).
     */
    final class ChatResponse {
        public final String content;          // text content (may be null)
        public final List<ToolCall> toolCalls; // tool call list (may be empty)
        public final String finishReason;
        public final JsonObject raw;          // raw response JSON
        public final String reasoning;        // reasoning text (may be null)

        public ChatResponse(String content, List<ToolCall> toolCalls,
                            String finishReason, JsonObject raw) {
            this(content, toolCalls, finishReason, raw, null);
        }

        public ChatResponse(String content, List<ToolCall> toolCalls,
                            String finishReason, JsonObject raw, String reasoning) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.finishReason = finishReason;
            this.raw = raw;
            this.reasoning = reasoning;
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean hasReasoning() {
            return reasoning != null && !reasoning.isBlank();
        }
    }

    /** Tool-call DTO. */
    final class ToolCall {
        public final String id;
        public final String name;
        public final String argumentsJson;

        public ToolCall(String id, String name, String argumentsJson) {
            this.id = id;
            this.name = name;
            this.argumentsJson = argumentsJson;
        }
    }
}