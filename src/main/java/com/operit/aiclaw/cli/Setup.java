package com.operit.aiclaw.cli;

import com.operit.aiclaw.llm.RequestStyle;
import com.operit.aiclaw.util.Profile;
import com.operit.aiclaw.util.ProfileStore;
import com.operit.aiclaw.util.ThinkingControlConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Interactive configuration wizard for {@code aiclaw setup}.
 *
 * <p>The wizard creates the {@code ~/.aiclaw/} directory tree and one or more profiles. It can be
 * driven non-interactively with {@code --no-input} for scripted installs.</p>
 */
public class Setup {
    private record StylePreset(String name, String defaultBaseUrl, String hint) {}

    private static final List<StylePreset> STYLE_PRESETS = List.of(
            new StylePreset("OpenAI General (standard chat/completions)", "https://api.openai.com/v1", "OpenAI, DeepSeek, Qwen, Moonshot, vLLM, and most OpenAI-compatible gateways"),
            new StylePreset("Gemini General (OpenAI-compatible with nested generationConfig)", "https://generativelanguage.googleapis.com/v1beta/openai", "Google Gemini official OpenAI-compatible endpoint"),
            new StylePreset("Claude General (Anthropic Messages API)", "https://api.anthropic.com", "Anthropic official API; requests use /v1/messages")
    );

    private static final String[] MODE_DESCRIPTIONS = {
            "1 -> reasoning.effort (effort; mutually exclusive with 3)",
            "2 -> thinking.type (enabled / adaptive / disabled; mutually exclusive with 4)",
            "3 -> generationConfig.thinkingConfig.thinkingBudget (budget; mutually exclusive with 1)",
            "4 -> thinking.type=enabled (fixed reasoning; remove the field to disable; mutually exclusive with 2)",
            "5 -> reasoning.mode=pro (cannot be used alone)"
    };

    private final BufferedReader in;
    private final boolean noInput;
    private final ProfileStore store;

    public Setup() { this(false); }
    public Setup(boolean noInput) {
        this.in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.noInput = noInput;
        this.store = new ProfileStore();
    }

    public int run() {
        try {
            printBanner();
            store.ensureDirectories();
            System.out.println(Ansi.success("Created/confirmed directories: " + store.getHome()));
            System.out.println(Ansi.muted("  profiles/          " + store.getProfilesDir()));
            System.out.println(Ansi.muted("  config.properties  " + store.getGlobalConfig()));
            System.out.println();
            boolean first = true;
            boolean madeActive = store.activeProfileName() != null;
            while (true) {
                Profile profile = createOneProfile(first ? "First profile" : "Additional profile");
                if (profile == null) System.out.println(Ansi.warn("Skipped."));
                else {
                    store.save(profile);
                    System.out.println(Ansi.success("Saved: " + profile.getFile()));
                    if (!madeActive) {
                        store.setActiveProfile(profile.getName());
                        madeActive = true;
                        System.out.println(Ansi.success("Set as the active profile: " + profile.getName()));
                    }
                }
                first = false;
                if (noInput) break;
                System.out.println();
                if (!askYesNo("Add another profile?", false)) break;
                System.out.println();
            }
            System.out.println();
            System.out.println(Ansi.bold("---- Current status ----"));
            System.out.println("Config dir : " + store.getHome());
            System.out.println("Active     : " + (store.activeProfileName() == null ? "(none)" : store.activeProfileName()));
            List<Profile> all = store.listProfiles();
            System.out.println("Profiles   : " + (all.isEmpty() ? "(none)" : ""));
            for (Profile profile : all) {
                System.out.printf("  - %s  [%s]  style=%s  %s%n",
                        profile.getName(),
                        profile.getName().equals(store.activeProfileName()) ? "* active" : "         ",
                        profile.getRequestStyle().key(),
                        profile.hasApiKey() ? profile.maskedKey() : "(no key)");
            }
            System.out.println();
            System.out.println(Ansi.success("Configuration complete! You can now run: aiclaw coder 'hello'"));
            return 0;
        } catch (IOException e) {
            System.err.println(Ansi.error("Setup failed: " + e.getMessage()));
            return 2;
        }
    }

    private Profile createOneProfile(String label) throws IOException {
        System.out.println(Ansi.bold("== " + label + " =="));
        RequestStyle style = askStyle();
        if (style == null) return null;
        StylePreset preset = STYLE_PRESETS.get(style.ordinal());
        String defaultName = style.key().replace("-general", "");
        String name = askName(defaultName);
        if (name == null || name.isBlank()) return null;
        if (store.exists(name) && !askYesNo("Profile '" + name + "' already exists. Overwrite?", false)) return null;
        String baseUrl = noInput ? preset.defaultBaseUrl() : prompt("Base URL [" + preset.defaultBaseUrl() + "]: ", preset.defaultBaseUrl());
        if (baseUrl.isBlank()) { System.err.println(Ansi.error("Base URL must not be blank")); return null; }
        String model = "";
        if (!noInput) {
            System.out.println(Ansi.muted("The model may be left blank; press Ctrl+F in the REPL to choose one."));
            model = prompt("Model (optional custom model ID): ", "");
        }
        String modes = askModes();
        ThinkingControlConfig controls = askThinkingControls(modes);
        String apiKey = noInput ? "" : promptSecret("API key" + (style == RequestStyle.OPENAI_GENERAL && baseUrl.contains("localhost") ? " (optional for local endpoints)" : "") + ": ");
        String description = noInput ? "" : prompt("Description (optional): ", "");
        return new Profile(name, store.getProfilesDir().resolve(name + ".properties"), apiKey, baseUrl, model, style, modes, "", "", description, controls);
    }

    private String askModes() throws IOException {
        if (noInput) return "";
        System.out.println();
        System.out.println("Select thinking modes (comma-separated; press Enter to skip):");
        for (String description : MODE_DESCRIPTIONS) System.out.println("  " + description);
        while (true) {
            String answer = prompt("-> ", "");
            if (answer.isBlank()) return "";
            List<String> errors = ThinkingControlConfig.validateModes(answer);
            List<Integer> modes = ThinkingControlConfig.parseModes(answer);
            if (errors.isEmpty() && !modes.isEmpty()) return modes.stream().map(String::valueOf).collect(Collectors.joining(","));
            if (errors.isEmpty()) System.out.println(Ansi.warn("Enter at least one mode from 1-5."));
            else for (String error : errors) System.out.println(Ansi.error("  " + error));
        }
    }

    /** Collects the new thinking.N.options/value/off fields used by Ctrl+A. */
    private ThinkingControlConfig askThinkingControls(String modes) throws IOException {
        List<Integer> modeNumbers = ThinkingControlConfig.parseModes(modes);
        if (modeNumbers.isEmpty()) return ThinkingControlConfig.empty();
        Properties properties = new Properties();
        for (int mode : modeNumbers) {
            switch (mode) {
                case 1, 3 -> askOptionsFor(properties, mode, true);
                case 2 -> askOptionsFor(properties, mode, false);
                case 4, 5 -> System.out.println(Ansi.muted("Mode " + mode + " is a protocol-defined toggle; Ctrl+A toggles field presence and needs no extra configuration."));
                default -> throw new IllegalStateException("unsupported thinking mode: " + mode);
            }
        }
        return ThinkingControlConfig.fromProperties(properties);
    }

    private void askOptionsFor(Properties properties, int mode, boolean required) throws IOException {
        if (noInput) return;
        System.out.println();
        System.out.println("Configure Ctrl+A options for mode " + mode + " (" + MODE_DESCRIPTIONS[mode - 1] + "):");
        String label = required ? "Options (comma-separated; required for Ctrl+A, e.g. low,high,max): " : "Options (comma-separated; blank falls back to an enabled/disabled toggle): ";
        String options = prompt(label, "");
        if (options.isBlank()) {
            if (required) System.out.println(Ansi.warn("No options configured; mode " + mode + " will not appear in Ctrl+A."));
            return;
        }
        properties.setProperty("thinking." + mode + ".options", options);
        String value = prompt("Initial value (blank uses the first option): ", "");
        if (!value.isBlank()) properties.setProperty("thinking." + mode + ".value", value);
        String defaultOff = mode == 3 ? "remove" : "none";
        String off = prompt("Disable action (remove=delete the field; blank defaults to " + defaultOff + "): ", "");
        if (!off.isBlank()) properties.setProperty("thinking." + mode + ".off", off);
    }

    private RequestStyle askStyle() throws IOException {
        if (noInput) return RequestStyle.OPENAI_GENERAL;
        System.out.println("Select a request style (controls request-body construction, not model availability):");
        int index = 1;
        for (StylePreset preset : STYLE_PRESETS) {
            System.out.printf("  %d) %s%n", index++, preset.name());
            System.out.println("       base: " + preset.defaultBaseUrl());
            System.out.println("       hint: " + preset.hint());
        }
        System.out.println("  c) Custom style key (advanced)");
        String answer = prompt("-> ", "1");
        try {
            int selected = Integer.parseInt(answer.trim()) - 1;
            if (selected >= 0 && selected < STYLE_PRESETS.size()) return RequestStyle.values()[selected];
        } catch (NumberFormatException ignored) {
            // Fall through to the custom-style prompt below.
        }
        if (answer.trim().equalsIgnoreCase("c")) return RequestStyle.fromKey(prompt("Custom style key: ", "openai-general"));
        return RequestStyle.OPENAI_GENERAL;
    }

    private String askName(String defaultName) throws IOException {
        return prompt("Profile name (used by `aiclaw profile use <name>`) [" + defaultName + "]: ", defaultName).trim();
    }

    private String prompt(String label, String defaultValue) throws IOException {
        System.out.print(label); System.out.flush();
        if (noInput) return defaultValue;
        String line = in.readLine(); if (line == null) return defaultValue;
        line = line.trim(); return line.isEmpty() ? defaultValue : line;
    }

    private boolean askYesNo(String label, boolean defaultYes) throws IOException {
        if (noInput) return defaultYes;
        System.out.print(label + " [" + (defaultYes ? "Y/n" : "y/N") + "]: "); System.out.flush();
        String line = in.readLine(); return line == null || line.isBlank() ? defaultYes : line.trim().toLowerCase().startsWith("y");
    }

    private String promptSecret(String label) throws IOException {
        System.out.print(label); System.out.flush();
        if (noInput) return "";
        if (System.console() != null) { char[] password = System.console().readPassword(); return password == null ? "" : new String(password).trim(); }
        String line = in.readLine(); return line == null ? "" : line.trim();
    }

    private void printBanner() {
        System.out.println();
        System.out.println(Ansi.bold("aiclaw interactive setup"));
        System.out.println("Creates ~/.aiclaw/ configuration files and profiles step by step.");
        System.out.println("Use `aiclaw profile use <name>` to switch between services.");
        System.out.println();
    }
}