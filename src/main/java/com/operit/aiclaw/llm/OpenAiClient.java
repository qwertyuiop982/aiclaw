package com.operit.aiclaw.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.operit.aiclaw.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic {@link LlmClient} that adapts to three {@link RequestStyle}s:
 *
 * <ul>
 *   <li>OPENAI_GENERAL - {@code POST {baseUrl}/chat/completions} (OpenAI / DeepSeek / Qwen / vLLM, ...)</li>
 *   <li>GEMINI_GENERAL - {@code POST {baseUrl}/chat/completions} (Gemini OpenAI-compatible endpoint)</li>
 *   <li>CLAUDE_GENERAL  - {@code POST {baseUrl}/messages} (Anthropic native Messages API)</li>
 * </ul>
 *
 * <p>Thinking parameters are supplied as a {@link ThinkingConfig}; the active {@code RequestStyle}
 * determines where the fields end up in the request body. Users supply keys and values verbatim;
 * aiclaw does not perform any model-specific magic.
 */
public class OpenAiClient implements LlmClient {

    private final String baseUrl;
    private final String apiKey;
    private final RequestStyle style;
    private final int timeoutSeconds;
    private final HttpClient http;
    private ThinkingConfig thinking;

    public OpenAiClient(String baseUrl, String apiKey, int timeoutSeconds) {
        this(baseUrl, apiKey, timeoutSeconds, null, RequestStyle.OPENAI_GENERAL);
    }

    public OpenAiClient(String baseUrl, String apiKey, int timeoutSeconds, ThinkingConfig thinking) {
        this(baseUrl, apiKey, timeoutSeconds, thinking, RequestStyle.OPENAI_GENERAL);
    }

    public OpenAiClient(String baseUrl, String apiKey, int timeoutSeconds,
                        ThinkingConfig thinking, RequestStyle style) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.style = style == null ? RequestStyle.OPENAI_GENERAL : style;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.thinking = thinking == null ? new ThinkingConfig() : thinking;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
                .build();
    }

    public void setThinking(ThinkingConfig thinking) {
        this.thinking = thinking == null ? new ThinkingConfig() : thinking;
    }
    public ThinkingConfig getThinking() { return thinking; }
    public RequestStyle getStyle() { return style; }

    /** One entry in the active endpoint's {@code /models} listing. */
    public record ModelInfo(String id, String displayName) {
        public ModelInfo {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("model id must not be blank");
            }
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
        }
    }

    /**
     * Load the model list from the same API root as chat completions ({@code /models}).
     * Compatible with OpenAI-style {@code data:[]} responses and others that wrap models in
     * a top-level {@code models:[]} array. No hard-coded provider list is consulted.
     */
    public List<ModelInfo> listModels() {
        String endpoint = resolveModelsEndpoint();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            if (style == RequestStyle.CLAUDE_GENERAL) {
                request.header("x-api-key", apiKey);
                request.header("anthropic-version", "2023-06-01");
            } else {
                request.header("Authorization", "Bearer " + apiKey);
            }
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmException("model list request failed: " + e.getMessage(), e);
        }

        String responseBody = response.body();
        if (response.statusCode() / 100 != 2) {
            throw new LlmException("model list API error " + response.statusCode() + ": "
                    + truncate(responseBody, 500), response.statusCode());
        }
        try {
            return parseModelList(responseBody);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("cannot parse model list response: " + e.getMessage(), e);
        }
    }

    /** URL actually requested by {@link #listModels()}, exposed for the REPL status line. */
    public String modelsEndpoint() {
        return resolveModelsEndpoint();
    }

    private static List<ModelInfo> parseModelList(String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonArray entries = null;
        if (root.isJsonArray()) {
            entries = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            entries = arrayMember(object, "data");
            if (entries == null) entries = arrayMember(object, "models");
        }
        if (entries == null) {
            throw new LlmException("model list response has no data/models array: " + truncate(body, 300));
        }

        // Preserve the server's order, deduplicating by id.
        Map<String, ModelInfo> models = new LinkedHashMap<>();
        for (JsonElement entry : entries) {
            String id = null;
            String displayName = null;
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                id = entry.getAsString();
            } else if (entry.isJsonObject()) {
                JsonObject object = entry.getAsJsonObject();
                id = stringMember(object, "id", "model", "name");
                displayName = stringMember(object, "display_name", "displayName", "name");
            }
            if (id != null && !id.isBlank()) {
                models.putIfAbsent(id, new ModelInfo(id, displayName));
            }
        }
        if (models.isEmpty()) {
            throw new LlmException("model list response contains no usable model ids");
        }
        return List.copyOf(models.values());
    }

    private static JsonArray arrayMember(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonArray()
                ? object.getAsJsonArray(name) : null;
    }

    private static String stringMember(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()
                    && object.get(name).isJsonPrimitive()
                    && object.getAsJsonPrimitive(name).isString()) {
                return object.get(name).getAsString();
            }
        }
        return null;
    }

    @Override
    public ChatResponse chat(String model, List<Map<String, Object>> messages,
                             Map<String, Object> options) {
        return chatWithTools(model, messages, null, options);
    }

    @Override
    public ChatResponse chatWithTools(String model,
                                      List<Map<String, Object>> messages,
                                      List<Map<String, Object>> tools,
                                      Map<String, Object> options) {
        try {
            JsonObject body = buildBody(model, messages, tools, options);
            return execute(body);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM request build failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the request body. All style-specific differences are concentrated here.
     */
    private JsonObject buildBody(String model, List<Map<String, Object>> messages,
                                 List<Map<String, Object>> tools,
                                 Map<String, Object> options) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        if (style == RequestStyle.CLAUDE_GENERAL) {
            // Claude Messages API: system is hoisted, content must be an array.
            return buildClaudeBody(body, model, messages, tools, options);
        }

        // OPENAI_GENERAL / GEMINI_GENERAL: chat/completions.
        body.add("messages", Json.gson().toJsonTree(messages));

        if (tools != null && !tools.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Map<String, Object> t : tools) {
                arr.add(Json.gson().toJsonTree(t));
            }
            body.add("tools", arr);
        }

        if (options != null) {
            for (Map.Entry<String, Object> e : options.entrySet()) {
                body.add(e.getKey(), Json.gson().toJsonTree(e.getValue()));
            }
        }

        thinking.apply(body, style);
        return body;
    }

    /** Claude Messages API body: system is a top-level string, messages are content arrays,
     *  tools use {@code input_schema} instead of {@code parameters}. */
    private JsonObject buildClaudeBody(JsonObject body, String model,
                                       List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools,
                                       Map<String, Object> options) {
        body.addProperty("max_tokens", 8192); // Claude requires this.

        String systemText = null;
        List<Map<String, Object>> nonSystem = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            Object role = m.get("role");
            if (role != null && "system".equals(role.toString())) {
                Object c = m.get("content");
                if (systemText == null) systemText = c == null ? "" : c.toString();
                else systemText = systemText + "\n\n" + (c == null ? "" : c.toString());
            } else {
                nonSystem.add(m);
            }
        }
        if (systemText != null) {
            body.addProperty("system", systemText);
        }

        JsonArray claudeMsgs = new JsonArray();
        for (Map<String, Object> m : nonSystem) {
            String role = String.valueOf(m.get("role"));
            String claudeRole = switch (role) {
                case "assistant" -> "assistant";
                case "tool"      -> "user"; // tool result -> user (Claude style)
                default          -> "user";
            };
            JsonObject cm = new JsonObject();
            cm.addProperty("role", claudeRole);

            if ("tool".equals(role)) {
                // tool result -> user message's tool_result block
                String toolCallId = String.valueOf(m.get("tool_call_id"));
                String content = String.valueOf(m.get("content"));
                JsonArray blocks = new JsonArray();
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_result");
                block.addProperty("tool_use_id", toolCallId);
                block.addProperty("content", content);
                blocks.add(block);
                cm.add("content", blocks);
            } else if ("assistant".equals(role) && m.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()) {
                JsonArray blocks = new JsonArray();
                Object c = m.get("content");
                if (c != null && !c.toString().isBlank()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", c.toString());
                    blocks.add(text);
                }
                for (Object raw : calls) {
                    if (!(raw instanceof Map<?, ?> call)) continue;
                    Object rawFn = call.get("function");
                    if (!(rawFn instanceof Map<?, ?> fn)) continue;
                    JsonObject use = new JsonObject();
                    use.addProperty("type", "tool_use");
                    use.addProperty("id", String.valueOf(call.get("id") == null ? "" : call.get("id")));
                    use.addProperty("name", String.valueOf(fn.get("name") == null ? "" : fn.get("name")));
                    Object rawArgs = fn.get("arguments");
                    try {
                        use.add("input", JsonParser.parseString(rawArgs == null ? "{}" : rawArgs.toString()));
                    } catch (Exception ignored) {
                        use.add("input", new JsonObject());
                    }
                    blocks.add(use);
                }
                cm.add("content", blocks);
            } else {
                // Plain text message.
                Object c = m.get("content");
                if (c instanceof List) cm.add("content", Json.gson().toJsonTree(c));
                else cm.addProperty("content", c == null ? "" : c.toString());
            }
            claudeMsgs.add(cm);
        }
        body.add("messages", claudeMsgs);

        if (tools != null && !tools.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Map<String, Object> t : tools) {
                JsonObject nt = new JsonObject();
                Object name = t.get("name");
                Object desc = t.get("description");
                Object params = t.get("parameters");
                if (name != null) nt.addProperty("name", name.toString());
                if (desc != null) nt.addProperty("description", desc.toString());
                if (params != null) nt.add("input_schema", Json.gson().toJsonTree(params));
                arr.add(nt);
            }
            body.add("tools", arr);
        }

        if (options != null) {
            for (Map.Entry<String, Object> e : options.entrySet()) {
                String k = e.getKey();
                if ("parameters".equals(k)) continue; // already mapped to input_schema
                body.add(k, Json.gson().toJsonTree(e.getValue()));
            }
        }

        thinking.apply(body, style);
        return body;
    }

    private ChatResponse execute(JsonObject body) {
        String endpoint = resolveEndpoint();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            if (style == RequestStyle.CLAUDE_GENERAL) {
                request.header("x-api-key", apiKey);
                request.header("anthropic-version", "2023-06-01");
            } else {
                request.header("Authorization", authorizationValue(apiKey));
            }
        }
        HttpRequest req = request
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new LlmException("HTTP request failed: " + e.getMessage(), e);
        }

        String responseBody = resp.body();
        int status = resp.statusCode();
        if (status / 100 != 2) {
            throw new LlmException(
                    "LLM API error " + status + ": " + truncate(responseBody, 500),
                    status);
        }

        return parseResponse(responseBody);
    }

    private ChatResponse parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        // Claude response: { content: [{type:"text", text:"..."}, {type:"tool_use", id, name, input}], stop_reason }
        if (style == RequestStyle.CLAUDE_GENERAL) {
            return parseClaudeResponse(root);
        }

        // OpenAI / Gemini-compatible chat/completions response.
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("LLM response has no choices: " + truncate(body, 300));
        }

        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject msg = first.getAsJsonObject("message");
        if (msg == null) {
            throw new LlmException("LLM response missing message: " + truncate(body, 300));
        }

        String content = msg.has("content") && !msg.get("content").isJsonNull()
                ? msg.get("content").getAsString() : null;

        // Reasoning: providers name this field differently (reasoning_content / reasoning), all optional.
        String reasoning = stringMember(msg, "reasoning_content", "reasoning");

        List<ToolCall> calls = new ArrayList<>();
        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            JsonArray arr = msg.getAsJsonArray("tool_calls");
            for (JsonElement el : arr) {
                JsonObject tc = el.getAsJsonObject();
                String id = tc.has("id") ? tc.get("id").getAsString() : "";
                String name = "";
                String argsJson = "{}";
                if (tc.has("function") && tc.get("function").isJsonObject()) {
                    JsonObject fn = tc.getAsJsonObject("function");
                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                        name = fn.get("name").getAsString();
                    }
                    if (fn.has("arguments")) {
                        JsonElement argsEl = fn.get("arguments");
                        if (argsEl.isJsonObject()) argsJson = argsEl.toString();
                        else if (argsEl.isJsonPrimitive()) argsJson = argsEl.getAsString();
                    }
                }
                calls.add(new ToolCall(id, name, argsJson));
            }
        }

        String finish = first.has("finish_reason") && !first.get("finish_reason").isJsonNull()
                ? first.get("finish_reason").getAsString() : null;

        return new ChatResponse(content, calls, finish, root, reasoning);
    }

    private ChatResponse parseClaudeResponse(JsonObject root) {
        // content is an array; we may see text, thinking, and tool_use blocks.
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        if (root.has("content") && root.get("content").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("content");
            for (JsonElement el : arr) {
                JsonObject block = el.getAsJsonObject();
                String type = block.has("type") ? block.get("type").getAsString() : "";
                if ("text".equals(type) && block.has("text")) {
                    if (text.length() > 0) text.append('\n');
                    text.append(block.get("text").getAsString());
                } else if ("thinking".equals(type) && block.has("thinking")) {
                    // Claude extended thinking block: { type: "thinking", thinking: "...", signature }
                    if (reasoning.length() > 0) reasoning.append('\n');
                    reasoning.append(block.get("thinking").getAsString());
                } else if ("redacted_thinking".equals(type)) {
                    // Provider withheld the reasoning text; nothing readable to show.
                    if (reasoning.length() == 0) reasoning.append("[redacted]");
                } else if ("tool_use".equals(type)) {
                    String id = block.has("id") ? block.get("id").getAsString() : "";
                    String name = block.has("name") ? block.get("name").getAsString() : "";
                    JsonElement input = block.has("input") ? block.get("input") : new JsonObject();
                    calls.add(new ToolCall(id, name, input.toString()));
                }
            }
        }
        String finish = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                ? root.get("stop_reason").getAsString() : null;
        return new ChatResponse(text.toString(), calls, finish, root,
                reasoning.length() == 0 ? null : reasoning.toString());
    }

    private static String authorizationValue(String key) {
        // The key is an opaque provider credential. Never require or add an sk- prefix.
        return "Bearer " + key;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private String resolveModelsEndpoint() {
        String chatEndpoint = resolveEndpoint();
        String chatSuffix = style == RequestStyle.CLAUDE_GENERAL ? "/messages" : "/chat/completions";
        if (!chatEndpoint.endsWith(chatSuffix)) {
            throw new IllegalStateException("cannot derive models endpoint from " + chatEndpoint);
        }
        return chatEndpoint.substring(0, chatEndpoint.length() - chatSuffix.length()) + "/models";
    }

    /**
     * Combine {@code baseUrl} and {@code style.endpoint} while avoiding a doubled {@code /v1}.
     *
     * <p>Rules:
     * <ul>
     *   <li>Trim trailing slashes on {@code baseUrl}.</li>
     *   <li>If {@code baseUrl} already contains {@code /v1} (e.g. {@code https://api.openai.com/v1})
     *       and the endpoint is {@code /chat/completions} (OpenAI / Gemini style), join directly
     *       to avoid {@code /v1/v1/chat/completions} 404s.</li>
     *   <li>If {@code baseUrl} does not contain {@code /v1} (e.g. {@code https://api.anthropic.com})
     *       and the endpoint is {@code /v1/messages} (Claude), the result is
     *       {@code https://api.anthropic.com/v1/messages}.</li>
     * </ul>
     */
    private String resolveEndpoint() {
        String bu = baseUrl == null ? "" : baseUrl.trim();
        while (bu.endsWith("/")) bu = bu.substring(0, bu.length() - 1);
        String ep = style.endpoint();
        boolean baseHasV1 = bu.contains("/v1");
        if (style == RequestStyle.CLAUDE_GENERAL) {
            // Anthropic: always /v1/messages (baseUrl is usually https://api.anthropic.com).
            if (baseHasV1) {
                // baseUrl already includes /v1 (e.g. https://api.anthropic.com/v1), strip it from ep.
                return bu + ep.substring(3);
            }
            return bu + ep;
        }
        // OPENAI_GENERAL / GEMINI_GENERAL: endpoint is /chat/completions
        if (baseHasV1) {
            return bu + ep;
        }
        // baseUrl has no /v1, so add it.
        return bu + "/v1" + ep;
    }
}