package com.operit.aiclaw.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reasoning / thinking parameters. The caller supplies keys and values; this class only stores
 * them and assembles the request body according to the active {@link RequestStyle}.
 *
 * <p>Numeric mode field conventions (these are project-level, not inferred from the model):
 * <ul>
 *   <li>1 - {@code reasoning.effort}</li>
 *   <li>2 - {@code thinking.type}</li>
 *   <li>3 - {@code generationConfig.thinkingConfig.thinkingBudget}</li>
 *   <li>4 - {@code thinking.type} (present means fixed reasoning; remove the field to disable)</li>
 *   <li>5 - {@code reasoning.mode} (Ctrl+A always sets the value to {@code pro})</li>
 * </ul>
 */
public final class ThinkingConfig {
    public static final String EFFORT_FIELD = "reasoning.effort";
    public static final String TYPE_FIELD = "thinking.type";
    public static final String BUDGET_FIELD = "generationConfig.thinkingConfig.thinkingBudget";
    public static final String MODE_FIELD = "reasoning.mode";
    public static final String FIXED_TYPE_VALUE = "enabled";
    public static final String FIXED_MODE_VALUE = "pro";

    /** Resolved top-level entries (no dots in key). */
    private final Map<String, String> topLevel = new LinkedHashMap<>();
    /** Dotted-path entries. Under OPENAI_GENERAL they live under {@code extra_body}; other
     *  styles place them in the appropriate container. */
    private final Map<String, String> extraBody = new LinkedHashMap<>();

    public ThinkingConfig() {}

    /**
     * Add a single key=value pair.
     * <ul>
     *   <li>{@code extra_body.foo=bar} strips the prefix and stores a nested entry</li>
     *   <li>any other key containing a dot is stored as a nested entry</li>
     *   <li>a flat key (no dot) is stored at the top level</li>
     * </ul>
     */
    public ThinkingConfig add(String key, String value) {
        if (key == null || key.isBlank()) return this;
        if (key.startsWith("extra_body.")) {
            extraBody.put(key.substring("extra_body.".length()), value);
        } else if (key.contains(".")) {
            extraBody.put(key, value);
        } else {
            topLevel.put(key, value);
        }
        return this;
    }

    /** Returns the request field for one configured thinking mode. */
    public static String fieldForMode(int mode) {
        return switch (mode) {
            case 1 -> EFFORT_FIELD;
            case 2, 4 -> TYPE_FIELD;
            case 3 -> BUDGET_FIELD;
            case 5 -> MODE_FIELD;
            default -> throw new IllegalArgumentException("unsupported thinking mode: " + mode);
        };
    }

    /** True only for the budget mode, whose value is read from {@code thinking.budget} in
     *  legacy profiles. */
    public static boolean isBudgetMode(int mode) {
        return mode == 3;
    }

    /**
     * Set the exact request value for a mode. Modes 4 and 5 are protocol-fixed: callers may
     * only enable them (or remove the field via {@link #removeMode(int)}).
     */
    public ThinkingConfig setModeValue(int mode, String value) {
        if (value == null || value.isBlank()) return removeMode(mode);
        if (mode == 4) return add(TYPE_FIELD, FIXED_TYPE_VALUE);
        if (mode == 5) return add(MODE_FIELD, FIXED_MODE_VALUE);
        return add(fieldForMode(mode), value);
    }

    /** Returns the exact active value for a mode, or {@code null} if the field is absent. */
    public String getModeValue(int mode) {
        String field = fieldForMode(mode);
        String top = topLevel.get(field);
        return top != null ? top : extraBody.get(field);
    }

    /** Removes a mode's field so it is absent from the next request. */
    public ThinkingConfig removeMode(int mode) {
        String field = fieldForMode(mode);
        topLevel.remove(field);
        extraBody.remove(field);
        return this;
    }

    /**
     * Apply a comma-separated list of modes using the legacy level/budget split. For example
     * {@code addMode("1,5", "high", null)} sets mode 1 to {@code high} and mode 5 to
     * {@code pro}.
     */
    public ThinkingConfig addMode(String modes, String level, String budget) {
        if (modes == null || modes.isBlank()) return this;
        boolean hasLevel = level != null && !level.isBlank();
        boolean hasBudget = budget != null && !budget.isBlank();
        for (String raw : modes.split(",")) {
            String value = raw.trim();
            if (!value.matches("[1-5]")) {
                if (hasLevel) add(value, level);
                continue;
            }
            int mode = Integer.parseInt(value);
            if (mode == 4 || mode == 5) {
                setModeValue(mode, "enabled");
            } else if (isBudgetMode(mode)) {
                if (hasBudget) setModeValue(mode, budget);
            } else if (hasLevel) {
                setModeValue(mode, level);
            }
        }
        return this;
    }

    /** Parse {@code --thinking-arg} entries: either arbitrary {@code key=value} or numeric
     *  mode shorthand like {@code 1=high}. */
    public ThinkingConfig addAll(List<String> pairs) {
        if (pairs == null) return this;
        for (String pair : pairs) {
            if (pair == null) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                addMode(pair, null, null);
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.matches("[1-5](,[1-5])*")) {
                addMode(key, value, value);
            } else {
                add(key, value);
            }
        }
        return this;
    }

    public boolean isEmpty() { return topLevel.isEmpty() && extraBody.isEmpty(); }
    public Map<String, String> topLevel() { return topLevel; }
    public Map<String, String> extraBody() { return extraBody; }

    /** Compatibility helper for {@code eft-<level>}. */
    public ThinkingConfig setLevel(String modes, String level) {
        return addMode(modes, level, null);
    }

    /** Compatibility helper for {@code rty-<budget>}. */
    public ThinkingConfig setBudget(String modes, String budget) {
        return addMode(modes, null, budget);
    }

    /**
     * Apply the configured values to a request body.
     * <ul>
     *   <li>OPENAI_GENERAL: dotted fields are placed under {@code extra_body}.</li>
     *   <li>GEMINI_GENERAL: dotted fields are placed under {@code generationConfig}, with the
     *       leading {@code generationConfig.} segment stripped from BUDGET_FIELD paths.</li>
     *   <li>CLAUDE_GENERAL: dotted fields are placed at the body root.</li>
     * </ul>
     */
    public void apply(JsonObject body, RequestStyle style) {
        if (body == null) return;
        for (Map.Entry<String, String> entry : topLevel.entrySet()) {
            body.add(entry.getKey(), coerce(entry.getValue()));
        }
        if (extraBody.isEmpty()) return;

        if (style == null || style == RequestStyle.OPENAI_GENERAL) {
            JsonObject container = body.has("extra_body") && body.get("extra_body").isJsonObject()
                    ? body.getAsJsonObject("extra_body") : new JsonObject();
            for (Map.Entry<String, String> entry : extraBody.entrySet()) {
                setNested(container, entry.getKey(), coerce(entry.getValue()));
            }
            body.add("extra_body", container);
            return;
        }

        if (style == RequestStyle.GEMINI_GENERAL) {
            JsonObject container = body.has("generationConfig") && body.get("generationConfig").isJsonObject()
                    ? body.getAsJsonObject("generationConfig") : new JsonObject();
            for (Map.Entry<String, String> entry : extraBody.entrySet()) {
                String path = entry.getKey();
                if (path.startsWith("generationConfig.")) {
                    path = path.substring("generationConfig.".length());
                }
                setNested(container, path, coerce(entry.getValue()));
            }
            body.add("generationConfig", container);
            return;
        }

        // Claude-style requests keep dotted fields at the body root.
        for (Map.Entry<String, String> entry : extraBody.entrySet()) {
            setNested(body, entry.getKey(), coerce(entry.getValue()));
        }
    }

    /** Compatibility helper: default to OPENAI_GENERAL. */
    public void apply(JsonObject body) {
        apply(body, RequestStyle.OPENAI_GENERAL);
    }

    private static void setNested(JsonObject root, String dottedPath, JsonElement value) {
        String[] parts = dottedPath.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!current.has(part) || !current.get(part).isJsonObject()) {
                current.add(part, new JsonObject());
            }
            current = current.getAsJsonObject(part);
        }
        current.add(parts[parts.length - 1], value);
    }

    /** Coerce a JSON-like value string into a JsonElement (booleans, numbers, quoted strings,
     *  or raw JSON). */
    private static JsonElement coerce(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false")) {
            return new com.google.gson.JsonPrimitive(Boolean.parseBoolean(normalized));
        }
        try {
            if (normalized.matches("-?\\d+")) return new com.google.gson.JsonPrimitive(Long.parseLong(normalized));
            if (normalized.matches("-?\\d+\\.\\d+")) return new com.google.gson.JsonPrimitive(Double.parseDouble(normalized));
        } catch (NumberFormatException ignored) {
            // Fall through to string/JSON parsing.
        }
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            return JsonParser.parseString(normalized);
        } catch (Exception ignored) {
            return new com.google.gson.JsonPrimitive(normalized);
        }
    }

    @Override
    public String toString() {
        return "ThinkingConfig{top=" + topLevel + ", extra=" + extraBody + "}";
    }
}