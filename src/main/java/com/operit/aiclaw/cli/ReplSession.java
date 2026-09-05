package com.operit.aiclaw.cli;

import com.operit.aiclaw.llm.LlmClient;
import com.operit.aiclaw.llm.OpenAiClient;
import com.operit.aiclaw.llm.RequestStyle;
import com.operit.aiclaw.llm.ThinkingConfig;
import com.operit.aiclaw.util.Config;
import com.operit.aiclaw.util.ThinkingControlConfig;

import java.util.List;

/**
 * Mutable REPL session state.
 *
 * <p>Centralises the effective {@code baseUrl}, {@code apiKey}, {@code model}, {@code style}, and
 * {@code thinking} for a single interactive session. Immersive commands ({@code esc} / {@code eft-} /
 * {@code rty-} / {@code tk:} / {@code sys:} / {@code modst-} / {@code sklp:} / {@code style:}) mutate
 * this object and then call {@link #rebuildLlm()} to obtain a fresh {@link OpenAiClient} which the
 * caller hands back to the agent.</p>
 *
 * <p>This indirection keeps {@link Config} immutable and the agent's public contract intact while
 * still allowing in-REPL changes to take effect on the next model call.</p>
 */
public final class ReplSession {

    private final Config cfg;
    private String baseUrl;
    private String apiKey;
    private String model;
    private RequestStyle style;
    private ThinkingConfig thinking;
    private String thinkingModes;
    private final ThinkingControlConfig thinkingControls;

    public ReplSession(Config cfg, String baseUrl, String apiKey, String model,
                       RequestStyle style, ThinkingConfig thinking,
                       String thinkingModes, ThinkingControlConfig thinkingControls) {
        this.cfg = cfg;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.style = style == null ? RequestStyle.OPENAI_GENERAL : style;
        this.thinking = thinking == null ? new ThinkingConfig() : thinking;
        this.thinkingModes = thinkingModes == null ? "" : thinkingModes;
        this.thinkingControls = thinkingControls == null ? ThinkingControlConfig.empty() : thinkingControls;
    }

    /** Builds a fresh {@link LlmClient} that reflects the current session state. */
    public LlmClient rebuildLlm() {
        return createClient();
    }

    /** Lists the models advertised by the current session's base URL and API key. */
    public List<OpenAiClient.ModelInfo> listModels() {
        return createClient().listModels();
    }

    /** URL that {@link #listModels()} actually calls (for diagnostic display). */
    public String modelsEndpoint() {
        return createClient().modelsEndpoint();
    }

    private OpenAiClient createClient() {
        return new OpenAiClient(baseUrl, apiKey, cfg.getTimeoutSeconds(), thinking, style);
    }

    // --- getters ---
    public String getBaseUrl()  { return baseUrl; }
    public String getApiKey()   { return apiKey; }
    public String getModel()    { return model; }
    public RequestStyle getStyle() { return style; }
    public ThinkingConfig getThinking() { return thinking; }
    public String getThinkingModes() { return thinkingModes; }
    public ThinkingControlConfig getThinkingControls() { return thinkingControls; }
    public Config getConfig()   { return cfg; }

    // --- setters (used by immersive command handlers) ---
    public void setBaseUrl(String u) { this.baseUrl = u; }
    public void setApiKey(String k)  { this.apiKey = k; }
    public void setModel(String m)   { this.model = m; }
    public void setStyle(RequestStyle s) { this.style = s == null ? RequestStyle.OPENAI_GENERAL : s; }
    public void setThinking(ThinkingConfig t) { this.thinking = t == null ? new ThinkingConfig() : t; }
    public void setThinkingModes(String modes) { this.thinkingModes = modes == null ? "" : modes; }
}