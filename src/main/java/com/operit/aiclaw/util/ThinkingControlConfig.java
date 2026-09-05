package com.operit.aiclaw.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * User-defined Ctrl+A thinking controls stored in a profile.
 *
 * <p>Each configured mode may define a current value, selectable values, and an off action:
 * {@code thinking.1.value}, {@code thinking.1.options}, and {@code thinking.1.off}. The values
 * remain opaque strings so endpoint/model-specific behavior stays in user configuration.</p>
 */
public final class ThinkingControlConfig {
    private static final int FIRST_MODE = 1;
    private static final int LAST_MODE = 5;

    /** A single mode's user-defined values. */
    public record Mode(int number, String value, List<String> options, String off) {
        public Mode {
            if (number < FIRST_MODE || number > LAST_MODE) {
                throw new IllegalArgumentException("unsupported thinking mode: " + number);
            }
            value = normalize(value);
            options = options == null ? List.of() : List.copyOf(options);
            off = normalize(off);
        }

        public boolean isConfigured() {
            return !value.isEmpty() || !options.isEmpty() || !off.isEmpty();
        }
    }

    private final Map<Integer, Mode> modes;

    private ThinkingControlConfig(Map<Integer, Mode> modes) {
        this.modes = Collections.unmodifiableMap(new LinkedHashMap<>(modes));
    }

    public static ThinkingControlConfig empty() {
        return new ThinkingControlConfig(Map.of());
    }

    /** Reads {@code thinking.N.value/options/off} fields from profile properties. */
    public static ThinkingControlConfig fromProperties(Properties properties) {
        if (properties == null || properties.isEmpty()) return empty();

        Map<Integer, Mode> parsed = new LinkedHashMap<>();
        for (int mode = FIRST_MODE; mode <= LAST_MODE; mode++) {
            String prefix = "thinking." + mode + ".";
            Mode setting = new Mode(
                    mode,
                    properties.getProperty(prefix + "value"),
                    splitOptions(properties.getProperty(prefix + "options")),
                    properties.getProperty(prefix + "off"));
            if (setting.isConfigured()) parsed.put(mode, setting);
        }
        return new ThinkingControlConfig(parsed);
    }

    /** Writes only the explicitly configured fields into a newly-created Properties object. */
    public void writeTo(Properties properties) {
        if (properties == null) return;
        for (Mode setting : modes.values()) {
            String prefix = "thinking." + setting.number() + ".";
            if (!setting.value().isEmpty()) properties.setProperty(prefix + "value", setting.value());
            if (!setting.options().isEmpty()) properties.setProperty(prefix + "options", String.join(",", setting.options()));
            if (!setting.off().isEmpty()) properties.setProperty(prefix + "off", setting.off());
        }
    }

    public Mode mode(int number) {
        return modes.get(number);
    }

    public boolean isEmpty() {
        return modes.isEmpty();
    }

    public Map<Integer, Mode> modes() {
        return modes;
    }

    /** Parses a comma-separated 1-5 mode list, preserving order and removing duplicates. */
    public static List<Integer> parseModes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<Integer> parsed = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.matches("[1-5]")) parsed.add(Integer.parseInt(value));
        }
        return List.copyOf(parsed);
    }

    /**
     * Validates the user-defined compatibility rules:
     * mode 1 and 3 conflict, mode 2 and 4 conflict, and mode 5 cannot stand alone.
     */
    public static List<String> validateModes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        List<String> errors = new ArrayList<>();
        Set<Integer> parsed = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String value = part.trim();
            if (!value.matches("[1-5]")) {
                errors.add("unsupported thinking mode '" + value + "' (expected 1-5)");
                continue;
            }
            int mode = Integer.parseInt(value);
            if (!parsed.add(mode)) errors.add("duplicate thinking mode " + mode);
        }
        errors.addAll(validateModeNumbers(parsed));
        return List.copyOf(errors);
    }

    /**
     * Extracts only explicit numeric mode syntax from --thinking-arg or /thinking tokens.
     * Arbitrary dotted keys remain intentionally outside mode validation.
     */
    public static List<Integer> numericModesFromArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) return List.of();
        Set<Integer> modes = new LinkedHashSet<>();
        for (String argument : arguments) {
            if (argument == null) continue;
            int equals = argument.indexOf('=');
            String key = (equals < 0 ? argument : argument.substring(0, equals)).trim();
            if (key.matches("[1-5](,[1-5])*")) {
                modes.addAll(parseModes(key));
            }
        }
        return List.copyOf(modes);
    }

    /** Merges profile/CLI mode text with numeric mode tokens while preserving first occurrence order. */
    public static List<Integer> mergeModes(String configuredModes, List<String> arguments) {
        Set<Integer> merged = new LinkedHashSet<>(parseModes(configuredModes));
        merged.addAll(numericModesFromArguments(arguments));
        return List.copyOf(merged);
    }

    /** Formats a validated numeric mode collection for ReplSession display and later validation. */
    public static String formatModes(Collection<Integer> modes) {
        if (modes == null || modes.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Integer mode : modes) {
            if (mode == null) continue;
            if (out.length() > 0) out.append(',');
            out.append(mode);
        }
        return out.toString();
    }

    /** Validates compatibility without re-parsing text, used after CLI numeric modes are merged. */
    public static List<String> validateModeNumbers(Collection<Integer> modes) {
        if (modes == null || modes.isEmpty()) return List.of();
        Set<Integer> parsed = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (Integer mode : modes) {
            if (mode == null || mode < FIRST_MODE || mode > LAST_MODE) {
                errors.add("unsupported thinking mode '" + mode + "' (expected 1-5)");
            } else {
                parsed.add(mode);
            }
        }
        if (parsed.contains(1) && parsed.contains(3)) {
            errors.add("thinking modes 1 (reasoning.effort) and 3 (thinkingBudget) are mutually exclusive");
        }
        if (parsed.contains(2) && parsed.contains(4)) {
            errors.add("thinking modes 2 and 4 both use thinking.type and are mutually exclusive");
        }
        if (parsed.size() == 1 && parsed.contains(5)) {
            errors.add("thinking mode 5 (reasoning.mode) cannot be used alone");
        }
        return List.copyOf(errors);
    }

    /**
     * Mode 4 and mode 5 have fixed protocol values. Reject misleading profile values rather than
     * silently sending a different request than the user expects.
     */
    public static List<String> validateControls(String modes, ThinkingControlConfig controls) {
        if (controls == null || controls.isEmpty()) return List.of();
        List<String> errors = new ArrayList<>();
        for (int mode : parseModes(modes)) {
            Mode setting = controls.mode(mode);
            if (setting == null) continue;
            if (mode == 4) {
                if (!setting.value().isEmpty() && !"enabled".equalsIgnoreCase(setting.value())) {
                    errors.add("thinking.4.value must be enabled when mode 4 is configured");
                }
                if (!setting.options().isEmpty()) {
                    errors.add("thinking.4.options is unsupported because mode 4 is a fixed on/off control");
                }
                if (!setting.off().isEmpty() && !"remove".equalsIgnoreCase(setting.off())) {
                    errors.add("thinking.4.off must be remove because mode 4 disables by deleting thinking.type");
                }
            }
            if (mode == 5) {
                if (!setting.value().isEmpty() && !"pro".equalsIgnoreCase(setting.value())) {
                    errors.add("thinking.5.value must be pro when mode 5 is configured");
                }
                if (!setting.options().isEmpty()) {
                    errors.add("thinking.5.options is unsupported because mode 5 is fixed to reasoning.mode=pro");
                }
                if (!setting.off().isEmpty() && !"remove".equalsIgnoreCase(setting.off())) {
                    errors.add("thinking.5.off must be remove because mode 5 disables by deleting reasoning.mode");
                }
            }
        }
        return List.copyOf(errors);
    }

    private static List<String> splitOptions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String value = normalize(part);
            if (!value.isEmpty()) values.add(value);
        }
        return List.copyOf(values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
