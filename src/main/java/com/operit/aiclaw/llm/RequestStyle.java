package com.operit.aiclaw.llm;

/**
 * Request style: decides how aiclaw builds the request body for a given endpoint. It does
 * <em>not</em> restrict which models can be used; the style only changes wire format.
 *
 * <p>Design principles:
 * <ul>
 *   <li>{@code OPENAI_GENERAL}: any endpoint that speaks {@code POST /v1/chat/completions}.
 *     Default body: {@code { model, messages, tools?, temperature?, ... }}.
 *     Thinking fields live at the top level ({@code reasoning_effort}, {@code reasoning.mode}).
 *   </li>
 *   <li>{@code GEMINI_GENERAL}: OpenAI-compatible, but thinking fields are nested under
 *     {@code generationConfig.thinkingConfig.*}.</li>
 *   <li>{@code CLAUDE_GENERAL}: Anthropic Messages API ({@code POST /v1/messages}) with
 *     {@code system} hoisted to the top level and {@code input_schema} tool definitions.
 *     Thinking fields: {@code thinking.type} / {@code thinking.budget_tokens}.</li>
 * </ul>
 *
 * <p>No hard-coded model-to-field mapping: aiclaw just sends whatever key the user configured.
 */
public enum RequestStyle {

    /** OpenAI General (default): {@code /chat/completions}, all thinking fields flat. The
     * endpoint already exposes {@code /v1} in its base URL, so no extra prefix is added. */
    OPENAI_GENERAL("openai-general", "/chat/completions", false),

    /** Gemini General: OpenAI-compatible, but thinking fields are nested under
     * {@code generationConfig.thinkingConfig.*}. */
    GEMINI_GENERAL("gemini-general", "/chat/completions", true),

    /** Anthropic Claude: uses {@code /messages} with a structurally different body. */
    CLAUDE_GENERAL("claude-general", "/v1/messages", true);

    private final String key;
    private final String endpoint;
    private final boolean nestThinking;

    RequestStyle(String key, String endpoint, boolean nestThinking) {
        this.key = key;
        this.endpoint = endpoint;
        this.nestThinking = nestThinking;
    }

    public String key() { return key; }
    public String endpoint() { return endpoint; }
    public boolean nestsThinking() { return nestThinking; }

    /** Parse a user-provided style key; unknown values fall back to {@link #OPENAI_GENERAL}. */
    public static RequestStyle fromKey(String s) {
        if (s == null || s.isBlank()) return OPENAI_GENERAL;
        String v = s.trim().toLowerCase();
        for (RequestStyle r : values()) {
            if (r.key.equals(v) || r.name().equalsIgnoreCase(v)) return r;
        }
        return OPENAI_GENERAL;
    }
}