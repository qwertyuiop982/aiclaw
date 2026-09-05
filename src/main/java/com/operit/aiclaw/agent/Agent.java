package com.operit.aiclaw.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.operit.aiclaw.cli.Ansi;
import com.operit.aiclaw.llm.LlmClient;
import com.operit.aiclaw.llm.LlmException;
import com.operit.aiclaw.tools.ArtifactStore;
import com.operit.aiclaw.tools.Tool;
import com.operit.aiclaw.tools.ToolRegistry;
import com.operit.aiclaw.tools.ToolResult;
import com.operit.aiclaw.tools.ToolStatus;
import com.operit.aiclaw.util.Json;
import com.operit.aiclaw.util.ToolAuditJournal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent: holds the system prompt, the conversation history, and the available tools, and runs
 * the ReAct loop on top of an {@link LlmClient}.
 *
 * <p>Usage:
 * <pre>
 *   Agent agent = new Agent(app, llm, tools);
 *   String reply = agent.ask("Hello");
 * </pre>
 *
 * <p>Internal loop:
 * <ol>
 *   <li>Send the current messages to the LLM.</li>
 *   <li>If the response carries {@code tool_calls}, execute each one and append the result
 *       as a {@code tool} message.</li>
 *   <li>Repeat until the LLM stops requesting tools or {@code maxIterations} is hit.</li>
 * </ol>
 *
 * <p>Multimodal: {@link #askWithImage(String, String)} appends a user message with
 * {@code content=[{text}, {image_url}]} for OpenAI-style image_url data URIs.
 */
public class Agent implements AutoCloseable {

    private final AgentApp app;
    private LlmClient llm;
    private final ToolRegistry tools;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final int maxIterations;
    private final ContextManager contextManager;
    private final ToolAuditJournal auditJournal;
    private final ArtifactStore artifactStore;
    private final List<String> lastReasoningChain = new ArrayList<>();

    public Agent(AgentApp app, LlmClient llm, ToolRegistry tools, int maxIterations) {
        this(app, llm, tools, maxIterations, new ContextManager(), null);
    }

    public Agent(AgentApp app, LlmClient llm, ToolRegistry tools, int maxIterations,
                 ContextManager contextManager) {
        this(app, llm, tools, maxIterations, contextManager, null, null);
    }

    public Agent(AgentApp app, LlmClient llm, ToolRegistry tools, int maxIterations,
                 ContextManager contextManager, ToolAuditJournal auditJournal) {
        this(app, llm, tools, maxIterations, contextManager, auditJournal, null);
    }

    public Agent(AgentApp app, LlmClient llm, ToolRegistry tools, int maxIterations,
                 ContextManager contextManager, ToolAuditJournal auditJournal,
                 ArtifactStore artifactStore) {
        this.app = app;
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.contextManager = contextManager == null ? new ContextManager() : contextManager;
        this.auditJournal = auditJournal;
        this.artifactStore = artifactStore;
        if (app.getSystemPrompt() != null && !app.getSystemPrompt().isBlank()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", app.getSystemPrompt());
            messages.add(sys);
        }
    }

    public AgentApp app() { return app; }
    public ToolRegistry tools() { return tools; }
    public List<Map<String, Object>> history() {
        return java.util.Collections.unmodifiableList(messages);
    }

    /**
     * Replace the underlying LLM client. Used by REPL immersive commands when switching
     * baseUrl / apiKey / model / style. The conversation history is <em>not</em> cleared, so
     * swapping models may produce inconsistent behavior with prior context.
     */
    public void setLlm(LlmClient newLlm) {
        if (newLlm == null) throw new IllegalArgumentException("llm must not be null");
        this.llm = newLlm;
    }

    /** Currently active LLM client (for REPL status display). */
    public LlmClient llm() { return llm; }

    /**
     * Send a user message and return the final text reply. The call may iterate through
     * multiple tool rounds internally before returning.
     */
    public String ask(String userMessage) {
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);
        return runLoop();
    }

    /**
     * Multimodal variant: send a user message that includes an image.
     *
     * @param text    the text part (may be empty, but a short caption is recommended)
     * @param dataUri an OpenAI-style {@code data:image/...;base64,...} URI
     */
    public String askWithImage(String text, String dataUri) {
        List<Map<String, Object>> parts = new ArrayList<>();

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", text == null ? "" : text);
        parts.add(textPart);

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUri);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);
        parts.add(imagePart);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", parts);
        messages.add(userMsg);
        return runLoop();
    }

    /**
     * Reasoning text captured during the most recent {@code ask()} / {@code askWithImage()} call.
     * Each round that produced reasoning is appended, in order. Empty when the model/endpoint
     * did not return any reasoning.
     */
    public List<String> lastReasoningChain() {
        return java.util.Collections.unmodifiableList(lastReasoningChain);
    }

    /** Whether the most recent call captured any reasoning. */
    public boolean hasLastReasoning() {
        return !lastReasoningChain.isEmpty();
    }

    private String runLoop() {
        contextManager.compact(messages);
        List<Map<String, Object>> toolDefs = tools.exportOpenAiTools();
        String lastContent = null;
        lastReasoningChain.clear();

        for (int iter = 0; iter < Math.max(1, maxIterations); iter++) {
            LlmClient.ChatResponse resp;
            try {
                if (toolDefs.isEmpty()) {
                    resp = llm.chat(app.getModel(), messages, app.getOptions());
                } else {
                    resp = llm.chatWithTools(app.getModel(), messages, toolDefs, app.getOptions());
                }
            } catch (LlmException e) {
                throw e;
            }

            // Persist the assistant turn before any tool execution so the model sees its own call.
            Map<String, Object> aMsg = new LinkedHashMap<>();
            aMsg.put("role", "assistant");
            aMsg.put("content", resp.content == null ? "" : resp.content);
            if (resp.hasToolCalls()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (int callIndex = 0; callIndex < resp.toolCalls.size(); callIndex++) {
                    LlmClient.ToolCall tc = resp.toolCalls.get(callIndex);
                    String callId = tc.id == null || tc.id.isBlank()
                            ? "call_" + iter + "_" + callIndex : tc.id;
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name);
                    fn.put("arguments", tc.argumentsJson);

                    Map<String, Object> wrapper = new LinkedHashMap<>();
                    wrapper.put("id", callId);
                    wrapper.put("type", "function");
                    wrapper.put("function", fn);
                    tcs.add(wrapper);
                }
                aMsg.put("tool_calls", tcs);
            }
            messages.add(aMsg);

            if (resp.hasReasoning()) {
                lastReasoningChain.add(resp.reasoning);
            }
            lastContent = resp.content;

            if (!resp.hasToolCalls()) {
                break;
            }

            // Execute the requested tools and append the results as tool messages.
            for (int callIndex = 0; callIndex < resp.toolCalls.size(); callIndex++) {
                LlmClient.ToolCall tc = resp.toolCalls.get(callIndex);
                String callId = tc.id == null || tc.id.isBlank()
                        ? "call_" + iter + "_" + callIndex : tc.id;
                String output = runTool(tc, callId);
                Map<String, Object> tMsg = new LinkedHashMap<>();
                tMsg.put("role", "tool");
                tMsg.put("tool_call_id", callId);
                tMsg.put("content", output);
                messages.add(tMsg);
            }
        }

        return lastContent == null ? "" : lastContent;
    }

    private String runTool(LlmClient.ToolCall tc, String callId) {
        long started = System.nanoTime();
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            args = parseArgs(tc.argumentsJson);
        } catch (Throwable e) {
            String result = "[error: invalid tool arguments: " + safeMessage(e) + "]";
            audit(callId, tc.name, args, "invalid_arguments", started, result);
            return result;
        }
        if (auditJournal != null) auditJournal.toolCallStarted(callId, tc.name, args);
        Tool tool = tools.get(tc.name);
        if (tool == null) {
            String result = "[error: unknown tool '" + tc.name + "']";
            audit(callId, tc.name, args, "unknown_tool", started, result);
            return result;
        }
        if (!tools.enabled(tc.name)) {
            String result = "[error: tool '" + tc.name + "' is disabled]";
            audit(callId, tc.name, args, "denied", started, result);
            return result;
        }
        try {
            String result = tool.execute(args);
            String modelResult = result;
            if (artifactStore != null && result != null && result.length() > 12000) {
                try {
                    ArtifactStore.ArtifactRef ref = artifactStore.put(tc.name, result);
                    modelResult = "{\"status\":\"success\",\"summary\":\"output stored as artifact\","
                            + "\"preview\":" + com.google.gson.JsonParser.parseString(Json.gson().toJson(truncate(result, 4000)))
                            + ",\"artifact_id\":\"" + ref.id() + "\",\"truncated\":true}";
                } catch (Exception ignored) {
                    modelResult = truncate(result, 12000) + "\n...(artifact unavailable)";
                }
            }
            System.out.println(Ansi.muted("[tool] " + tc.name + " -> " + truncate(modelResult, 200)));
            audit(callId, tc.name, args, "success", started, result);
            return modelResult;
        } catch (Throwable e) {
            String result = "[error: " + tc.name + " failed: " + safeMessage(e) + "]";
            audit(callId, tc.name, args, "error", started, result);
            return result;
        }
    }

    private void audit(String callId, String toolName, Map<String, Object> args,
                       String status, long started, String output) {
        if (auditJournal == null) return;
        auditJournal.toolCallCompleted(callId, toolName, args, status,
                (System.nanoTime() - started) / 1_000_000L, output);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Object> map = new LinkedHashMap<>();
            obj.entrySet().forEach(e -> map.put(e.getKey(), toJavaValue(e.getValue())));
            return map;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool arguments JSON", ex);
        }
    }

    private static Object toJavaValue(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            e.getAsJsonObject().entrySet().forEach(x -> out.put(x.getKey(), toJavaValue(x.getValue())));
            return out;
        }
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (com.google.gson.JsonElement item : e.getAsJsonArray()) out.add(toJavaValue(item));
            return out;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Close resources owned by tools, including persistent terminal sessions. */
    @Override
    public void close() {
        tools.close();
        if (auditJournal != null) auditJournal.close();
    }

    /** Clear the conversation history while keeping the system prompt. */
    public void reset() {
        messages.clear();
        if (app.getSystemPrompt() != null && !app.getSystemPrompt().isBlank()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", app.getSystemPrompt());
            messages.add(sys);
        }
    }
}