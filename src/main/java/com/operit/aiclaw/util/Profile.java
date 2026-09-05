package com.operit.aiclaw.util;

import com.operit.aiclaw.llm.RequestStyle;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A single LLM profile: a complete set of LLM connection parameters (api key, base url, model,
 * request style, thinking mode, etc.).
 *
 * <p>A profile is persisted as {@code profiles/<name>.properties}. Recognised fields:</p>
 * <ul>
 *   <li>{@code api.key}          - API key (required for real endpoints).</li>
 *   <li>{@code api.base.url}     - API base URL (required, falls back to {@link Config#DEFAULT_BASE_URL}).</li>
 *   <li>{@code api.model}        - Model name (optional; leave blank to choose interactively via Ctrl+F).</li>
 *   <li>{@code request.style}    - Request style key: openai-general / gemini-general / claude-general.</li>
 *   <li>{@code thinking.modes}   - Comma-separated thinking mode numbers, e.g. "1" or "1,5"; may be empty.</li>
 *   <li>{@code thinking.level}   - Legacy thinking-effort value (kept for backward compatibility).</li>
 *   <li>{@code thinking.budget}  - Legacy thinking-budget value (kept for backward compatibility).</li>
 *   <li>{@code thinking.N.*}
 *       <ul>
 *         <li>{@code thinking.N.value}   - Current value for mode {@code N}.</li>
 *         <li>{@code thinking.N.options} - Comma-separated selectable values for Ctrl+A.</li>
 *         <li>{@code thinking.N.off}     - Value applied when Ctrl+A toggles the mode off.</li>
 *       </ul>
 *   </li>
 *   <li>{@code description}      - Free-form description shown in {@code aiclaw profile show}.</li>
 * </ul>
 */
public final class Profile {

    private final String name;
    private final Path file;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final RequestStyle requestStyle;
    private final String thinkingModes;
    private final String thinkingLevel;
    private final String thinkingBudget;
    private final ThinkingControlConfig thinkingControls;
    private final String description;

    public Profile(String name, Path file, String apiKey, String baseUrl,
                   String model, String description) {
        this(name, file, apiKey, baseUrl, model, RequestStyle.OPENAI_GENERAL,
                "", "", "", description, ThinkingControlConfig.empty());
    }

    public Profile(String name, Path file, String apiKey, String baseUrl, String model,
                   RequestStyle requestStyle, String thinkingModes,
                   String thinkingLevel, String thinkingBudget, String description) {
        this(name, file, apiKey, baseUrl, model, requestStyle, thinkingModes,
                thinkingLevel, thinkingBudget, description, ThinkingControlConfig.empty());
    }

    public Profile(String name, Path file, String apiKey, String baseUrl, String model,
                   RequestStyle requestStyle, String thinkingModes,
                   String thinkingLevel, String thinkingBudget, String description,
                   ThinkingControlConfig thinkingControls) {
        this.name = name;
        this.file = file;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.requestStyle = requestStyle == null ? RequestStyle.OPENAI_GENERAL : requestStyle;
        this.thinkingModes = thinkingModes == null ? "" : thinkingModes;
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel;
        this.thinkingBudget = thinkingBudget == null ? "" : thinkingBudget;
        this.thinkingControls = thinkingControls == null ? ThinkingControlConfig.empty() : thinkingControls;
        this.description = description == null ? "" : description;
    }

    public static Profile fromProperties(String name, Path file, Properties properties) {
        RequestStyle style = RequestStyle.fromKey(properties.getProperty("request.style", "openai-general"));
        return new Profile(
                name,
                file,
                firstProperty(properties, "api.key", "api_key"),
                firstProperty(properties, "api.base.url", "base_url", Config.DEFAULT_BASE_URL),
                firstProperty(properties, "api.model", "model", Config.DEFAULT_MODEL),
                style,
                properties.getProperty("thinking.modes", ""),
                properties.getProperty("thinking.level", ""),
                properties.getProperty("thinking.budget", ""),
                properties.getProperty("description", ""),
                ThinkingControlConfig.fromProperties(properties)
        );
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        if (apiKey != null) properties.setProperty("api.key", apiKey);
        if (baseUrl != null) properties.setProperty("api.base.url", baseUrl);
        if (model != null) properties.setProperty("api.model", model);
        properties.setProperty("request.style", requestStyle.key());
        if (!thinkingModes.isEmpty()) properties.setProperty("thinking.modes", thinkingModes);
        if (!thinkingLevel.isEmpty()) properties.setProperty("thinking.level", thinkingLevel);
        if (!thinkingBudget.isEmpty()) properties.setProperty("thinking.budget", thinkingBudget);
        thinkingControls.writeTo(properties);
        if (!description.isEmpty()) properties.setProperty("description", description);
        return properties;
    }

    /**
     * Returns a copy with the supplied fields overridden. A {@code null} argument leaves the
     * corresponding field untouched. All other fields are inherited from this profile.
     */
    public Profile withOverrides(String apiKey, String baseUrl, String model,
                                 RequestStyle requestStyle, String thinkingModes,
                                 String thinkingLevel, String thinkingBudget,
                                 String description) {
        return new Profile(
                name, file,
                apiKey != null ? apiKey : this.apiKey,
                baseUrl != null ? baseUrl : this.baseUrl,
                model != null ? model : this.model,
                requestStyle != null ? requestStyle : this.requestStyle,
                thinkingModes != null ? thinkingModes : this.thinkingModes,
                thinkingLevel != null ? thinkingLevel : this.thinkingLevel,
                thinkingBudget != null ? thinkingBudget : this.thinkingBudget,
                description != null ? description : this.description,
                this.thinkingControls
        );
    }

    /** Returns a short, non-reversible form of the API key for display. */
    public String maskedKey() {
        if (apiKey == null || apiKey.isBlank()) return "(none)";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    public String getName() { return name; }
    public Path getFile() { return file; }
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public RequestStyle getRequestStyle() { return requestStyle; }
    public String getThinkingModes() { return thinkingModes; }
    public String getThinkingLevel() { return thinkingLevel; }
    public String getThinkingBudget() { return thinkingBudget; }
    public ThinkingControlConfig getThinkingControls() { return thinkingControls; }
    public String getDescription() { return description; }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    private static String firstProperty(Properties properties, String first, String second) {
        return firstProperty(properties, first, second, null);
    }

    private static String firstProperty(Properties properties, String first, String second, String fallback) {
        String value = properties.getProperty(first);
        if (value != null && !value.isBlank()) return value.trim();
        value = properties.getProperty(second);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public Map<String, String> summary() {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("name", name);
        summary.put("base_url", baseUrl);
        summary.put("model", model);
        summary.put("request_style", requestStyle.key());
        if (!thinkingModes.isEmpty()) summary.put("thinking_modes", thinkingModes);
        if (!thinkingLevel.isEmpty()) summary.put("thinking_level", thinkingLevel);
        if (!thinkingBudget.isEmpty()) summary.put("thinking_budget", thinkingBudget);
        if (!thinkingControls.isEmpty()) summary.put("thinking_controls", thinkingControls.modes().keySet().toString());
        summary.put("api_key", maskedKey());
        if (!description.isBlank()) summary.put("description", description);
        summary.put("file", file.toString());
        return summary;
    }
}