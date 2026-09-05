package com.operit.aiclaw.util;

import com.operit.aiclaw.llm.RequestStyle;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Effective configuration for a single CLI invocation.
 *
 * <p>{@code Config} aggregates three sources, in increasing priority:</p>
 * <ol>
 *   <li>Bundled defaults in this class ({@link #DEFAULT_BASE_URL}, {@link #DEFAULT_MODEL}).</li>
 *   <li>The active profile loaded from {@link ProfileStore} (api.key / api.base.url / api.model,
 *       request.style, thinking.modes / thinking.level / thinking.budget, thinking.N.*).</li>
 *   <li>Environment overrides (AICLAW_API_KEY / AICLAW_BASE_URL / AICLAW_MODEL) and
 *       per-invocation CLI overrides supplied through {@link #withCliOverrides}.</li>
 * </ol>
 *
 * <p>{@code Config} instances are immutable: every override returns a new instance, so callers
 * that need to mutate fields (the REPL session, for example) should keep their own copies.</p>
 */
public final class Config {

    /** Default base URL used when neither the active profile nor the environment supplies one. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** Default model identifier. Empty signals "no default; the user must pick one". */
    public static final String DEFAULT_MODEL = "";

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_TOOL_ITERATIONS = 32;

    private final String activeProfile;
    private final Path globalConfigDir;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final RequestStyle requestStyle;
    private final String thinkingModes;
    private final String thinkingLevel;
    private final String thinkingBudget;
    private final ThinkingControlConfig thinkingControls;
    private final Path agentsDir;
    private final int timeoutSeconds;
    private final int maxToolIterations;

    private Config(String activeProfile, Path globalConfigDir,
                   String apiKey, String baseUrl, String model, RequestStyle requestStyle,
                   String thinkingModes, String thinkingLevel, String thinkingBudget,
                   ThinkingControlConfig thinkingControls,
                   Path agentsDir, int timeoutSeconds, int maxToolIterations) {
        this.activeProfile = activeProfile;
        this.globalConfigDir = globalConfigDir;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.model = model == null ? "" : model;
        this.requestStyle = requestStyle == null ? RequestStyle.OPENAI_GENERAL : requestStyle;
        this.thinkingModes = thinkingModes == null ? "" : thinkingModes;
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel;
        this.thinkingBudget = thinkingBudget == null ? "" : thinkingBudget;
        this.thinkingControls = thinkingControls == null ? ThinkingControlConfig.empty() : thinkingControls;
        this.agentsDir = agentsDir;
        this.timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        this.maxToolIterations = maxToolIterations <= 0 ? DEFAULT_MAX_TOOL_ITERATIONS : maxToolIterations;
    }

    /**
     * Loads the effective configuration from the default {@link ProfileStore} (~/.aiclaw/) and the
     * current environment.
     */
    public static Config load() {
        return load(new ProfileStore(), System.getenv());
    }

    /**
     * Loads the effective configuration from an explicit {@link ProfileStore}. Test code uses this
     * to point the loader at a temporary directory.
     */
    public static Config load(ProfileStore store) {
        return load(store, System.getenv());
    }

    private static Config load(ProfileStore store, java.util.Map<String, String> env) {
        String activeName = store.activeProfileName();
        Profile profile = activeName == null ? null : store.get(activeName);
        Properties globals = store.loadGlobal();

        String apiKey = firstNonBlank(
                env == null ? null : env.get("AICLAW_API_KEY"),
                profile == null ? null : profile.getApiKey());
        String baseUrl = firstNonBlank(
                env == null ? null : env.get("AICLAW_BASE_URL"),
                profile == null ? null : profile.getBaseUrl(),
                DEFAULT_BASE_URL);
        String model = firstNonBlank(
                env == null ? null : env.get("AICLAW_MODEL"),
                profile == null ? null : profile.getModel());
        RequestStyle style = profile == null ? RequestStyle.OPENAI_GENERAL : profile.getRequestStyle();
        String thinkingModes = profile == null ? "" : profile.getThinkingModes();
        String thinkingLevel = profile == null ? "" : profile.getThinkingLevel();
        String thinkingBudget = profile == null ? "" : profile.getThinkingBudget();
        ThinkingControlConfig controls = profile == null ? ThinkingControlConfig.empty() : profile.getThinkingControls();

        Path agentsDir = resolveAgentsDir(globals);
        int timeoutSeconds = readInt(globals, "http.timeout.seconds", DEFAULT_TIMEOUT_SECONDS);
        int maxIterations = readInt(globals, "agent.max.tool.iterations", DEFAULT_MAX_TOOL_ITERATIONS);

        return new Config(activeName, store.getHome(),
                apiKey, baseUrl, model, style,
                thinkingModes, thinkingLevel, thinkingBudget, controls,
                agentsDir, timeoutSeconds, maxIterations);
    }

    /**
     * Returns a copy of this {@code Config} with the provided CLI overrides applied. {@code null}
     * arguments mean "do not override this field"; empty strings explicitly clear the value.
     */
    public Config withCliOverrides(String apiKey, String baseUrl, String model, RequestStyle requestStyle) {
        return new Config(activeProfile, globalConfigDir,
                apiKey != null ? apiKey : this.apiKey,
                baseUrl != null ? baseUrl : this.baseUrl,
                model != null ? model : this.model,
                requestStyle != null ? requestStyle : this.requestStyle,
                thinkingModes, thinkingLevel, thinkingBudget, thinkingControls,
                agentsDir, timeoutSeconds, maxToolIterations);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getActiveProfile() { return activeProfile; }
    public Path getGlobalConfigDir() { return globalConfigDir; }
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public RequestStyle getRequestStyle() { return requestStyle; }
    public String getThinkingModes() { return thinkingModes; }
    public String getThinkingLevel() { return thinkingLevel; }
    public String getThinkingBudget() { return thinkingBudget; }
    public ThinkingControlConfig getThinkingControls() { return thinkingControls; }
    public Path getAgentsDir() { return agentsDir; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxToolIterations() { return maxToolIterations; }

    private static Path resolveAgentsDir(Properties globals) {
        String configured = globals.getProperty("agents.dir");
        if (configured != null && !configured.isBlank()) return Paths.get(configured.trim());
        return Paths.get(System.getProperty("user.dir")).resolve("agents");
    }

    private static int readInt(Properties properties, String key, int fallback) {
        if (properties == null) return fallback;
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}