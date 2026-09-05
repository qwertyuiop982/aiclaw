package com.operit.aiclaw.cli;

import com.operit.aiclaw.agent.Agent;
import com.operit.aiclaw.agent.AgentApp;
import com.operit.aiclaw.agent.AgentLoader;
import com.operit.aiclaw.llm.LlmException;
import com.operit.aiclaw.llm.OpenAiClient;
import com.operit.aiclaw.llm.RequestStyle;
import com.operit.aiclaw.llm.ThinkingConfig;
import com.operit.aiclaw.tools.ArtifactReadTool;
import com.operit.aiclaw.tools.ArtifactStore;
import com.operit.aiclaw.tools.Tool;
import com.operit.aiclaw.tools.ToolRegistry;
import com.operit.aiclaw.util.Config;
import com.operit.aiclaw.util.ImageBase64;
import com.operit.aiclaw.util.ProfileStore;
import com.operit.aiclaw.util.ThinkingControlConfig;
import com.operit.aiclaw.util.ToolAuditJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * aiclaw CLI: argument parsing and command dispatch.
 *
 * <p>Notable options:</p>
 * <ul>
 *   <li>{@code --thinking-arg k=v} - pass through arbitrary reasoning / thinking parameter (repeatable).</li>
 *   <li>{@code --no-<tool>}        - blacklist a tool by name.</li>
 *   <li>{@code /tool enable|disable <name>} - REPL-time tool toggle.</li>
 *   <li>{@code @/abs/path.png}     - REPL multimodal: attach an image as a user message.</li>
 * </ul>
 */
public class Cli {
    private static final DateTimeFormatter CONVERSATION_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public int run(String[] args) {
        List<String> positional = new ArrayList<>();
        String modelOverride = null;
        String baseUrlOverride = null;
        String apiKeyOverride = null;
        String agentsDirOverride = null;
        String styleOverride = null;
        String thinkingModesOverride = null;
        String thinkingLevelOverride = null;
        String thinkingBudgetOverride = null;
        List<String> thinkingArgs = new ArrayList<>();
        Set<String> disabledTools = new LinkedHashSet<>();
        boolean forceInteractive = false;
        boolean noInput = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h":
                case "--help": {
                    printHelp();
                    return 0;
                }
                case "-i":
                case "--interactive": {
                    forceInteractive = true;
                    break;
                }
                case "--no-input": {
                    noInput = true;
                    break;
                }
                case "-v":
                case "--version": {
                    System.out.println("aiclaw 1.0.0");
                    return 0;
                }
                case "--model": {
                    if (++i >= args.length) die("--model requires a value");
                    modelOverride = args[i];
                    break;
                }
                case "--base-url": {
                    if (++i >= args.length) die("--base-url requires a value");
                    baseUrlOverride = args[i];
                    break;
                }
                case "--api-key": {
                    if (++i >= args.length) die("--api-key requires a value");
                    apiKeyOverride = args[i];
                    break;
                }
                case "--style": {
                    if (++i >= args.length) die("--style requires a value (openai-general / gemini-general / claude-general)");
                    styleOverride = args[i];
                    break;
                }
                case "--thinking-modes": {
                    if (++i >= args.length) die("--thinking-modes requires value (e.g. '1,5')");
                    thinkingModesOverride = args[i];
                    break;
                }
                case "--thinking-level": {
                    if (++i >= args.length) die("--thinking-level requires value");
                    thinkingLevelOverride = args[i];
                    break;
                }
                case "--thinking-budget": {
                    if (++i >= args.length) die("--thinking-budget requires value");
                    thinkingBudgetOverride = args[i];
                    break;
                }
                case "--agents-dir": {
                    if (++i >= args.length) die("--agents-dir requires a value");
                    agentsDirOverride = args[i];
                    break;
                }
                case "--thinking-arg": {
                    if (++i >= args.length) die("--thinking-arg requires key=value");
                    thinkingArgs.add(args[i]);
                    break;
                }
                default: {
                    if (arg.startsWith("--no-")) {
                        String name = arg.substring("--no-".length());
                        if (name.isBlank()) die("--no-<tool> requires a tool name");
                        disabledTools.add(name);
                    } else if (arg.startsWith("--thinking-arg=")) {
                        thinkingArgs.add(arg.substring("--thinking-arg=".length()));
                    } else {
                        positional.add(arg);
                    }
                }
            }
        }

        Config cfg = Config.load();
        if (baseUrlOverride != null || apiKeyOverride != null || modelOverride != null || styleOverride != null) {
            cfg = cfg.withCliOverrides(apiKeyOverride, baseUrlOverride, modelOverride,
                    styleOverride == null ? null : RequestStyle.fromKey(styleOverride));
        }

        // No positional argument: behavior depends on configuration.
        if (positional.isEmpty()) {
            return cmdDefault(cfg, agentsDirOverride, thinkingArgs, disabledTools, noInput,
                    modelOverride, baseUrlOverride, apiKeyOverride,
                    styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
        }
        String cmd = positional.remove(0).toLowerCase();
        switch (cmd) {
            case "list":
            case "ls": {
                return cmdList(cfg, agentsDirOverride);
            }
            case "run":
            case "chat":
            case "i": {
                String agentName = positional.isEmpty() ? "coder" : positional.remove(0);
                return cmdRun(cfg, agentName, agentsDirOverride, positional,
                        true, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                        styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
            }
            case "ask": {
                if (positional.isEmpty()) die("usage: aiclaw ask <agent> <message>");
                String agentName = positional.remove(0);
                String message = String.join(" ", positional);
                return cmdRun(cfg, agentName, agentsDirOverride, List.of(message),
                        false, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                        styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
            }
            case "show": {
                if (positional.isEmpty()) die("usage: aiclaw show <agent>");
                return cmdShow(cfg, positional.get(0), agentsDirOverride);
            }
            case "tools": {
                return cmdTools();
            }
            case "setup": {
                return new Setup(noInput).run();
            }
            case "profile": {
                return new ProfileCommand().run(positional.toArray(new String[0]));
            }
            case "config": {
                return cmdConfig(cfg, positional.toArray(new String[0]));
            }
            case "help": {
                printHelp();
                return 0;
            }
            default: {
                // aiclaw <agent>            -> run
                // aiclaw <agent> "msg"      -> ask (unless -i)
                // aiclaw <file.yaml>        -> run (load yaml)
                // aiclaw -i <agent> [msg]   -> run
                Path direct = Path.of(cmd);
                if (Files.exists(direct) && Files.isRegularFile(direct)) {
                    return cmdRun(cfg, cmd, agentsDirOverride, positional,
                            true, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                            styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
                }
                if (positional.isEmpty() || forceInteractive) {
                    // Single token, or interactive forced: enter REPL with this agent name.
                    return cmdRun(cfg, cmd, agentsDirOverride, positional,
                            true, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                            styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
                }
                // Extra words: first word is the agent, the rest is the ask message.
                String agentName = cmd;
                String message = String.join(" ", positional);
                return cmdRun(cfg, agentName, agentsDirOverride, List.of(message),
                        false, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                        styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
            }
        }
    }

    // -------- commands --------

    /** Smart dispatch used when no positional arguments are given. */
    private int cmdDefault(Config cfg, String agentsDirOverride,
                           List<String> thinkingArgs, Set<String> disabledTools,
                           boolean noInput,
                           String modelOverride, String baseUrlOverride, String apiKeyOverride,
                           String styleOverride,
                           String thinkingModesOverride, String thinkingLevelOverride, String thinkingBudgetOverride) {
        ProfileStore store = new ProfileStore();
        boolean hasAnyProfile = !store.listProfiles().isEmpty();

        // Never configured (~/.aiclaw missing or no profile present).
        if (!hasAnyProfile && !cfg.hasApiKey()) {
            System.out.println("Welcome to aiclaw - no LLM profile is configured yet.");
            System.out.println();
            // Do not force the wizard in non-interactive mode (pipes / scripts).
            if (noInput) {
                printQuickStart();
                return 0;
            }
            // Run setup automatically.
            return new Setup(false).run();
        }

        // Configured (profile exists or env has a key) but current effective config has no key.
        if (!cfg.hasApiKey()) {
            System.out.println("Warning: the current profile has no API key (and no environment variable either).");
            System.out.println("    active profile : " + (cfg.getActiveProfile() == null ? "(none)" : cfg.getActiveProfile()));
            System.out.println();
            System.out.println("You can:");
            System.out.println("  aiclaw setup                          # rerun the wizard");
            System.out.println("  aiclaw profile set-key <name> sk-xxx  # add a key to an existing profile");
            System.out.println("  export AICLAW_API_KEY=sk-xxx          # use an environment variable temporarily");
            System.out.println("  aiclaw --api-key sk-xxx coder ...     # one-shot CLI override");
            return 0;
        }

        // Configured with a key -> enter the default REPL.
        String defaultAgent = "coder";
        return cmdRun(cfg, defaultAgent, agentsDirOverride, List.of(),
                true, thinkingArgs, disabledTools, modelOverride, baseUrlOverride, apiKeyOverride,
                styleOverride, thinkingModesOverride, thinkingLevelOverride, thinkingBudgetOverride);
    }

    /** `aiclaw config ...`: show the effective configuration. */
    private int cmdConfig(Config cfg, String[] args) {
        ProfileStore store = new ProfileStore();
        String sub = args.length > 0 ? args[0] : "show";
        switch (sub) {
            case "show":
            case "list": {
                System.out.println("--- Effective configuration (aiclaw config show) ---");
                System.out.println("config dir      : " + cfg.getGlobalConfigDir());
                System.out.println("active profile  : " + (cfg.getActiveProfile() == null ? "(none)" : cfg.getActiveProfile()));
                System.out.println("base url        : " + cfg.getBaseUrl());
                System.out.println("model           : " + cfg.getModel());
                System.out.println("api key         : " + mask(cfg.getApiKey()));
                System.out.println("agents dir      : " + cfg.getAgentsDir());
                System.out.println("timeout         : " + cfg.getTimeoutSeconds() + "s");
                System.out.println("max iterations  : " + cfg.getMaxToolIterations());
                System.out.println();
                System.out.println("--- File locations ---");
                System.out.println(store.getHome());
                System.out.println(store.getProfilesDir());
                System.out.println(store.getGlobalConfig());
                return 0;
            }
            case "path":
            case "where": {
                System.out.println(store.getHome());
                System.out.println(store.getProfilesDir());
                System.out.println(store.getGlobalConfig());
                return 0;
            }
            default:
                System.err.println("usage: aiclaw config [show|path]");
                return 2;
        }
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) return "(none)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private int cmdList(Config cfg, String agentsDirOverride) {
        List<AgentApp> apps = discoverAgents(cfg, agentsDirOverride);
        if (apps.isEmpty()) {
            System.out.println("(no agent found)");
            return 0;
        }
        System.out.printf("%-18s  %-22s  %-22s  %s%n", "NAME", "MODEL", "TOOLS", "DESCRIPTION");
        System.out.println("-".repeat(90));
        for (AgentApp app : apps) {
            System.out.printf("%-18s  %-22s  %-22s  %s%n",
                    truncate(app.getName(), 18),
                    truncate(app.getModel() == null ? cfg.getModel() : app.getModel(), 22),
                    truncate(String.join(",", app.getTools()), 22),
                    app.getDescription() == null ? "" : truncate(app.getDescription(), 60));
        }
        System.out.println("\nagents dir: " + resolveAgentsDir(cfg, agentsDirOverride));
        return 0;
    }

    private int cmdShow(Config cfg, String agentName, String agentsDirOverride) {
        AgentApp app = loadAgent(cfg, agentName, agentsDirOverride);
        System.out.println("Name        : " + app.getName());
        System.out.println("Description : " + app.getDescription());
        System.out.println("Model       : " + (app.getModel() == null ? cfg.getModel() : app.getModel()));
        System.out.println("Tools       : " + (app.getTools().isEmpty() ? "(none)" : String.join(", ", app.getTools())));
        System.out.println("Options     : " + app.getOptions());
        System.out.println("--- system prompt ---");
        System.out.println(app.getSystemPrompt() == null ? "(empty)" : app.getSystemPrompt());
        return 0;
    }
private int cmdTools() {
        ToolRegistry registry = ToolRegistry.defaults();
        for (var tool : registry.all()) {
            System.out.println("- " + tool.name() + "  " + tool.description());
        }
        return 0;
    }

    private int cmdRun(Config cfg, String agentName, String agentsDirOverride,
                       List<String> remainingArgs, boolean interactive,
                       List<String> thinkingArgs, Set<String> disabledTools,
                       String modelOverride, String baseUrlOverride, String apiKeyOverride,
                       String styleOverride,
                       String thinkingModesOverride, String thinkingLevelOverride, String thinkingBudgetOverride) {
        AgentApp app = loadAgent(cfg, agentName, agentsDirOverride);

        // Bug fix: precedence must be CLI > agent.yaml > cfg.
        // The previous implementation `if (app.getModel() == null) app.setModel(cfg.getModel())`
        // made the cfg branch unreachable when the YAML contained a model, so --model was ignored.
        String effectiveModel = firstNonBlank(modelOverride, app.getModel(), cfg.getModel());
        if (effectiveModel != null) app.setModel(effectiveModel);

        // baseUrl / apiKey follow the same rule: CLI > cfg.
        String effectiveBaseUrl = firstNonBlank(baseUrlOverride, cfg.getBaseUrl());
        String effectiveApiKey = firstNonBlank(apiKeyOverride, cfg.getApiKey());

        ArtifactStore artifactStore = null;
        try {
            artifactStore = new ArtifactStore(cfg.getGlobalConfigDir().resolve("artifacts"));
        } catch (Exception e) {
            System.err.println("Warning: artifact store unavailable: " + e.getMessage());
        }
        ToolRegistry all = ToolRegistry.defaults();
        if (artifactStore != null) all.register(new ArtifactReadTool(artifactStore));
        ToolRegistry tools;
        if (app.getTools() == null || app.getTools().isEmpty()) {
            tools = all;
        } else {
            Set<String> names = new LinkedHashSet<>(app.getTools());
            tools = all.subset(names);
            for (String name : names) {
                if (!all.has(name)) {
                    System.err.println("Warning: unknown tool '" + name + "' requested by agent " + agentName);
                }
            }
        }
        // Apply the CLI blacklist.
        for (String name : disabledTools) tools.lock(name);

        if (!cfg.hasApiKey() && interactive) {
            System.err.println("Warning: API key not configured (set AICLAW_API_KEY or api.key in ~/.aiclaw/config.properties)");
        }

        // Style precedence: CLI --style > cfg.requestStyle > OPENAI_GENERAL.
        RequestStyle effectiveStyle = styleOverride != null ? RequestStyle.fromKey(styleOverride) : cfg.getRequestStyle();

        ThinkingConfig thinking = new ThinkingConfig();
        // CLI numeric mode shorthands participate in the same compatibility rules as profile modes.
        String configuredModes = firstNonBlank(thinkingModesOverride, cfg.getThinkingModes());
        String effLevel = firstNonBlank(thinkingLevelOverride, cfg.getThinkingLevel());
        String effBudget = firstNonBlank(thinkingBudgetOverride, cfg.getThinkingBudget());
        ThinkingControlConfig thinkingControls = cfg.getThinkingControls();
        List<Integer> modeNumbers = ThinkingControlConfig.mergeModes(configuredModes, thinkingArgs);
        String effModes = ThinkingControlConfig.formatModes(modeNumbers);
        Set<String> modeErrors = new LinkedHashSet<>();
        modeErrors.addAll(ThinkingControlConfig.validateModes(configuredModes));
        modeErrors.addAll(ThinkingControlConfig.validateModeNumbers(modeNumbers));
        modeErrors.addAll(ThinkingControlConfig.validateControls(effModes, thinkingControls));
        if (!modeErrors.isEmpty()) {
            for (String error : modeErrors) System.err.println("Invalid thinking configuration: " + error);
            return 2;
        }
        applyConfiguredThinking(thinking, effModes, effLevel, effBudget, thinkingControls,
                thinkingLevelOverride, thinkingBudgetOverride);
        // Arbitrary CLI keys are applied last so they remain the highest-priority override.
        thinking.addAll(thinkingArgs);
        if (!thinking.isEmpty()) {
            System.out.println("[thinking] " + thinking + "  style=" + effectiveStyle.key());
        }

        OpenAiClient llm = new OpenAiClient(effectiveBaseUrl, effectiveApiKey,
                cfg.getTimeoutSeconds(), thinking, effectiveStyle);
        ToolAuditJournal auditJournal = null;
        try {
            auditJournal = new ToolAuditJournal(cfg.getGlobalConfigDir().resolve("audit.jsonl"));
        } catch (Exception e) {
            System.err.println("Warning: audit journal unavailable: " + e.getMessage());
        }
        Agent agent = new Agent(app, llm, tools, cfg.getMaxToolIterations(), null, auditJournal, artifactStore);
        ReplSession session = new ReplSession(cfg, effectiveBaseUrl, effectiveApiKey,
                effectiveModel, effectiveStyle, thinking, effModes, thinkingControls);

        System.out.println("== aiclaw agent: " + app.getName() + " ==");
        if (app.getDescription() != null) System.out.println(app.getDescription());
        if (app.getGreeting() != null) System.out.println("\n" + app.getGreeting());
        System.out.println("Tools enabled: " + tools.enabledList().stream().map(Tool::name).toList());
        System.out.println("[model=" + modelStatus(effectiveModel) + "  style=" + effectiveStyle.key()
                + "  base=" + truncate(effectiveBaseUrl, 50) + "]");
        System.out.println();

        try {
            if (interactive) {
                runInteractive(agent, session, remainingArgs);
            } else {
                String userMessage = String.join(" ", remainingArgs);
                if (!hasSelectedModel(agent)) {
                    System.err.println("Error: no model is configured. Set --model / AICLAW_MODEL / api.model, or start the REPL and press Ctrl+F.");
                    return 2;
                }
                try {
                    String reply = agent.ask(userMessage);
                    printReasoning(agent);
                    System.out.println("\n" + reply);
                } catch (LlmException e) {
                    System.err.println("LLM error: " + e.getMessage());
                    return 2;
                }
            }
            return 0;
        } finally {
            agent.close();
        }
    }

    /** Applies profile-defined per-mode values while retaining legacy level/budget compatibility. */
    private static void applyConfiguredThinking(ThinkingConfig thinking, String modes,
                                                String legacyLevel, String legacyBudget,
                                                ThinkingControlConfig controls,
                                                String cliLevelOverride, String cliBudgetOverride) {
        if (modes == null || modes.isBlank()) return;
        for (int mode : ThinkingControlConfig.parseModes(modes)) {
            ThinkingControlConfig.Mode setting = controls == null ? null : controls.mode(mode);
            String configuredValue = setting == null ? "" : setting.value();
            String value;
            if (mode == 5) {
                value = ThinkingConfig.FIXED_MODE_VALUE;
            } else if (mode == 4) {
                value = ThinkingConfig.FIXED_TYPE_VALUE;
            } else if (ThinkingConfig.isBudgetMode(mode)) {
                value = firstNonBlank(cliBudgetOverride, configuredValue, legacyBudget);
            } else {
                value = firstNonBlank(cliLevelOverride, configuredValue, legacyLevel);
            }
            if (value != null && !value.isBlank()) thinking.setModeValue(mode, value);
        }
    }

    /**
     * Main REPL loop.
     *
     * <p>The default mode is chat; pressing Esc (or typing the literal word {@code esc}) toggles
     * command mode. In command mode, immersive directives such as {@code eft-} / {@code rty-} /
     * {@code tk:} / {@code sys:} / {@code modst-} / {@code sklp:} are intercepted and never sent
     * to the model. Press Esc again to return to chat mode.</p>
     *
     * @param session mutable session state (baseUrl / apiKey / model / style / thinking)
     */
    private void runInteractive(Agent agent, ReplSession session, List<String> firstArgs) {
        // false = chat mode; true = immersive command mode.
        boolean commandMode = false;
        try (ReplInput input = new ReplInput()) {
            if (!firstArgs.isEmpty()) {
                String first = String.join(" ", firstArgs);
                System.out.println("[you] " + first);
                if (!hasSelectedModel(agent)) {
                    printModelRequired();
                } else {
                    try {
                        String reply = agent.ask(first);
                        printReasoning(agent);
                        System.out.println("\n[" + agent.app().getName() + "] " + reply + "\n");
                    } catch (LlmException e) {
                        System.err.println("LLM error: " + e.getMessage());
                    }
                }
            }

            while (true) {
                String modeLabel = commandMode ? "CMD" : "CHAT";
                String modeMark = commandMode ? "!" : ">>>";
                System.out.print("[" + agent.app().getName() + " " + modeLabel + " " + modeMark + " you] ");
                String line = input.readLine();
                if (line == null) break;

                if (ReplInput.CTRL_Y.equals(line)) {
                    agent.reset();
                    commandMode = false;
                    System.out.println("[new conversation " + CONVERSATION_TIME.format(LocalDateTime.now())
                            + "] context cleared; model=" + modelStatus(agent.app().getModel()) + "\n");
                    continue;
                }
                if (ReplInput.CTRL_A.equals(line)) {
                    ThinkingPicker.open(input, agent, session);
                    continue;
                }
                if (ReplInput.CTRL_F.equals(line)) {
                    ModelPicker.open(input, agent, session);
                    continue;
                }

                // A physical ESC control character and the visible word "esc" both toggle command mode.
                if (ReplInput.ESCAPE.equals(line) || line.trim().equalsIgnoreCase("esc")) {
                    commandMode = !commandMode;
                    System.out.println(commandMode
                            ? "[command mode ON] type eft-/rty-/tk:/sys:/modst-/sklp:/style: commands; type esc again to return to chat mode.\n"
                            : "[chat mode ON] plain text is sent to the model.\n");
                    continue;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                // @path multimodal: only handled in chat mode, never sent from command mode.
                if (!commandMode && line.startsWith("@")) {
                    String path = line.substring(1).trim();
                    if (path.isEmpty() || !ImageBase64.looksLikeImage(path)) {
                        System.out.println("(use @/absolute/path/to/image.png to attach an image)\n");
                        continue;
                    }
                    if (!hasSelectedModel(agent)) {
                        printModelRequired();
                        continue;
                    }
                    try {
                        String dataUri = ImageBase64.toDataUri(path);
                        agent.askWithImage("[image attached]", dataUri);
                        System.out.println("[attached image] " + path);
                    } catch (Exception e) {
                        System.err.println("attach failed: " + e.getMessage());
                    }
                    continue;
                }

                // ============ Immersive commands (command mode only) ============
                if (commandMode) {

                    // eft-<level> -> adjust mode 1 reasoning.effort; mode 5 always stays pro.
                    if (line.toLowerCase().startsWith("eft-")) {
                        String level = line.substring(4).trim();
                        if (level.isEmpty()) {
                            System.out.println("(usage: eft-<level>)\n");
                            continue;
                        }
                        if (!ThinkingControlConfig.parseModes(session.getThinkingModes()).contains(1)
                                && session.getThinking().getModeValue(1) == null) {
                            System.out.println("(reasoning.effort is not configured for this session)\n");
                            continue;
                        }
                        ThinkingConfig t = session.getThinking();
                        t.setModeValue(1, level);
                        session.setThinking(t);
                        agent.setLlm(session.rebuildLlm());
                        System.out.println("[effort=" + level + "] " + t + "\n");
                        continue;
                    }

                    // rty-<budget> -> adjust mode 3 generationConfig.thinkingConfig.thinkingBudget.
                    if (line.toLowerCase().startsWith("rty-")) {
                        String budget = line.substring(4).trim();
                        if (budget.isEmpty()) {
                            System.out.println("(usage: rty-<budget>)\n");
                            continue;
                        }
                        if (!ThinkingControlConfig.parseModes(session.getThinkingModes()).contains(3)
                                && session.getThinking().getModeValue(3) == null) {
                            System.out.println("(thinkingBudget is not configured for this session)\n");
                            continue;
                        }
                        ThinkingConfig t = session.getThinking();
                        t.setModeValue(3, budget);
                        session.setThinking(t);
                        agent.setLlm(session.rebuildLlm());
                        System.out.println("[budget=" + budget + "] " + t + "\n");
                        continue;
                    }

                    // tk:<modes>:<level>:<budget> -> update modes and values per the validation rules.
                    if (line.toLowerCase().startsWith("tk:")) {
                        String body = line.substring(3).trim();
                        if (body.isEmpty()) {
                            System.out.println("(usage: tk:<modes>:<level>:<budget>)\n");
                            continue;
                        }
                        String[] segs = body.split(":", -1);
                        String modes = segs.length > 0 ? segs[0].trim() : "";
                        String level = segs.length > 1 ? segs[1].trim() : "";
                        String budget = segs.length > 2 ? segs[2].trim() : "";
                        String targetModes = modes.isEmpty() ? session.getThinkingModes() : modes;
                        if (targetModes.isBlank()) {
                            System.out.println("(thinking modes are required; configure thinking.modes or use tk:<modes>:...)\n");
                            continue;
                        }
                        List<String> errors = new ArrayList<>(ThinkingControlConfig.validateModes(targetModes));
                        errors.addAll(ThinkingControlConfig.validateControls(targetModes, session.getThinkingControls()));
                        if (!errors.isEmpty()) {
                            for (String error : errors) System.out.println("(invalid thinking configuration: " + error + ")");
                            System.out.println();
                            continue;
                        }

                        ThinkingConfig t = session.getThinking();
                        if (!modes.isEmpty()) {
                            for (int oldMode : ThinkingControlConfig.parseModes(session.getThinkingModes())) {
                                t.removeMode(oldMode);
                            }
                            session.setThinkingModes(targetModes);
                        }
                        for (int mode : ThinkingControlConfig.parseModes(targetModes)) {
                            if (mode == 5) {
                                t.setModeValue(5, ThinkingConfig.FIXED_MODE_VALUE);
                            } else if (ThinkingConfig.isBudgetMode(mode)) {
                                if (!budget.isEmpty()) t.setModeValue(mode, budget);
                            } else if (!level.isEmpty()) {
                                t.setModeValue(mode, level);
                            } else if (mode == 4 && t.getModeValue(4) == null) {
                                t.setModeValue(4, "enabled");
                            }
                        }
                        session.setThinking(t);
                        agent.setLlm(session.rebuildLlm());
                        System.out.println("[thinking] " + t + "\n");
                        continue;
                    }

                    // sys:"<text>" -> replace the system prompt (agent persona switch).
                    if (line.toLowerCase().startsWith("sys:")) {
                        String sys = unquoteAfter(line, "sys:");
                        if (sys.isEmpty()) {
                            System.out.println("(usage: sys:\"<new system prompt>\"; the full prompt goes inside the quotes)\n");
                            continue;
                        }
                        agent.app().setSystemPrompt(sys);
                        System.out.println("[system prompt updated, length=" + sys.length() + "]\n");
                        continue;
                    }

                    // modst-<model> -> switch the model id (no LLM rebuild; takes effect on the next ask).
                    if (line.toLowerCase().startsWith("modst-")) {
                        String m = line.substring("modst-".length()).trim();
                        if (m.isEmpty()) {
                            System.out.println("(usage: modst-<model-id>, or press Ctrl+F to choose from the current endpoint)\n");
                            continue;
                        }
                        String old = agent.app().getModel();
                        agent.app().setModel(m);
                        session.setModel(m);
                        System.out.println("[model: " + truncate(old, 30) + " -> " + m + "]\n");
                        continue;
                    }

                    // sklp:<key> -> switch the API key (rebuild LLM; never print the key, show only first/last four chars).
                    if (line.toLowerCase().startsWith("sklp:")) {
                        String k = line.substring(5).trim();
                        if (k.isEmpty()) {
                            System.out.println("(usage: sklp:<api-key>)\n");
                            continue;
                        }
                        session.setApiKey(k);
                        agent.setLlm(session.rebuildLlm());
                        System.out.println("[api-key updated: " + maskKey(k) + "]\n");
                        continue;
                    }

                    // base-ur-l:<url> -> switch the base URL. The dash-spaced prefix avoids accidental collision
                    // with model output containing <think> etc.
                    if (line.toLowerCase().startsWith("base-ur-l:")) {
                        String u = line.substring("base-ur-l:".length()).trim();
                        if (u.isEmpty()) {
                            System.out.println("(usage: base-ur-l:<url>)\n");
                            continue;
                        }
                        session.setBaseUrl(u);
                        agent.setLlm(session.rebuildLlm());
                        System.out.println("[base-url -> " + u + "]\n");
                        continue;
                    }

                    // style:<key> -> switch the request style (openai-general / gemini-general / claude-general).
                    if (line.toLowerCase().startsWith("style:")) {
                        String k = line.substring(6).trim();
                        try {
                            RequestStyle s = RequestStyle.fromKey(k);
                            session.setStyle(s);
                            agent.setLlm(session.rebuildLlm());
                            System.out.println("[style -> " + s.key() + "]\n");
                        } catch (IllegalArgumentException ex) {
                            System.out.println("(unknown style '" + k + "'. use: openai-general / gemini-general / claude-general)\n");
                        }
                        continue;
                    }

                } // commandMode

                // ============ Legacy slash commands (compatibility) ============

                if (line.equalsIgnoreCase("/exit") || line.equalsIgnoreCase("/quit")) break;
                if (line.equalsIgnoreCase("/reset")) {
                    agent.reset();
                    System.out.println("(history cleared)\n");
                    continue;
                }
                if (line.equalsIgnoreCase("/tools")) {
                    System.out.println("Tools enabled: " + agent.tools().enabledList().stream().map(Tool::name).toList());
                    System.out.println("Tools disabled: " + agent.tools().names().stream()
                            .filter(n -> !agent.tools().enabled(n)).toList());
                    System.out.println();
                    continue;
                }
                if (line.startsWith("/tool enable ") || line.startsWith("/tool disable ")
                        || line.equalsIgnoreCase("/tool")) {
                    handleToolCmd(line, agent);
                    continue;
                }
                if (line.startsWith("/thinking")) {
                    handleThinkingCmd(line, session, agent);
                    continue;
                }
                if (line.equalsIgnoreCase("/reasoning")) {
                    if (!agent.hasLastReasoning()) {
                        System.out.println("(no reasoning captured for the last reply; the model/endpoint may not return one)\n");
                    } else {
                        printReasoning(agent);
                    }
                    continue;
                }
                if (line.equalsIgnoreCase("/history")) {
                    System.out.println("History (" + agent.history().size() + " messages):");
                    for (var message : agent.history()) {
                        System.out.println("  - " + message.get("role"));
                    }
                    System.out.println();
                    continue;
                }
                if (line.equalsIgnoreCase("/state")) {
                    printSessionState(agent, session);
                    continue;
                }
                if (line.equalsIgnoreCase("/help")) {
                    printReplHelp();
                    continue;
                }

                // In command mode, unrecognized input must never be sent to the model.
                if (commandMode) {
                    System.out.println("(command mode: unknown command '" + line
                            + "'; type /help for commands, or press Esc to return to chat mode)\n");
                    continue;
                }

                if (!hasSelectedModel(agent)) {
                    printModelRequired();
                    continue;
                }

                try {
                    String reply = agent.ask(line);
                    printReasoning(agent);
                    System.out.println("\n[" + agent.app().getName() + "] " + reply + "\n");
                } catch (LlmException e) {
                    System.err.println("LLM error: " + e.getMessage());
                }
            }
            System.out.println("bye.");
        } catch (Exception e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }

    /**
     * Prints the reasoning from the most recent {@code ask()}, when the model / endpoint returned
     * a {@code reasoning_content}. Silent otherwise to avoid noise on endpoints that do not
     * surface reasoning.
     */
    private static void printReasoning(Agent agent) {
        if (!agent.hasLastReasoning()) return;
        System.out.println("\n[reasoning]");
        for (String chunk : agent.lastReasoningChain()) {
            System.out.println(chunk.strip());
        }
    }

    /** Strips a leading prefix and unquotes a balanced " or ' pair when present. */
    private static String unquoteAfter(String line, String prefix) {
        if (!line.startsWith(prefix)) return "";
        String rest = line.substring(prefix.length()).trim();
        if (rest.length() >= 2) {
            char firstChar = rest.charAt(0);
            char lastChar = rest.charAt(rest.length() - 1);
            if ((firstChar == '"' && lastChar == '"') || (firstChar == '\'' && lastChar == '\'')) {
                return rest.substring(1, rest.length() - 1);
            }
        }
        return rest;
    }

    /** Masks a key to {@code sk-****abcd} so echoes never leak the full value. */
    private static String maskKey(String k) {
        if (k == null || k.length() < 8) return "****";
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }

    private static boolean hasSelectedModel(Agent agent) {
        String model = agent.app().getModel();
        return model != null && !model.isBlank();
    }

    private static String modelStatus(String model) {
        return model == null || model.isBlank() ? "(not selected; Ctrl+F)" : model;
    }

    private static void printModelRequired() {
        System.out.println("(no model selected; press Ctrl+F to load models from the current endpoint, "
                + "then use Up/Down and Enter. In a script, pass --model or use modst-<model>.)\n");
    }

    /** Prints the current REPL session state (model / style / base / masked key / thinking). */
    private void printSessionState(Agent agent, ReplSession session) {
        System.out.println("---- REPL session state ----");
        System.out.println("agent          : " + agent.app().getName());
        System.out.println("model          : " + modelStatus(agent.app().getModel()));
        System.out.println("style          : " + session.getStyle().key());
        System.out.println("baseUrl        : " + session.getBaseUrl());
        System.out.println("apiKey         : " + maskKey(session.getApiKey()));
        System.out.println("thinking       : " + session.getThinking());
        System.out.println("thinking modes : " + (session.getThinkingModes().isBlank() ? "(none)" : session.getThinkingModes()));
        System.out.println("Ctrl+A modes   : " + (session.getThinkingControls().isEmpty()
                ? "(none)" : session.getThinkingControls().modes().keySet()));
        System.out.println("history size   : " + agent.history().size() + " messages");
        System.out.println("----------------------------\n");
    }

    private void handleToolCmd(String line, Agent agent) {
        String[] parts = line.split("\\s+");
        ToolRegistry registry = agent.tools();
        if (parts.length == 1) {
            // /tool  -- list every tool and its enable/disable status.
            for (Tool tool : registry.all()) {
                System.out.println("  " + (registry.enabled(tool.name()) ? "[x]" : "[ ]") + " " + tool.name());
            }
            System.out.println();
            return;
        }
        String op = parts[1].toLowerCase();
        String name = parts.length >= 3 ? parts[2] : "";
        if (name.isBlank() || !registry.has(name)) {
            System.out.println("(unknown tool: " + name + ")\n");
            return;
        }
        if (op.equals("enable")) {
            registry.enable(name);
            System.out.println("enabled: " + name + "\n");
        } else if (op.equals("disable")) {
            registry.disable(name);
            System.out.println("disabled: " + name + "\n");
        }
    }

    private void handleThinkingCmd(String line, ReplSession session, Agent agent) {
        String[] parts = line.split("\\s+", 2);
        if (parts.length == 1) {
            System.out.println("thinking: " + session.getThinking() + "\n");
            return;
        }

        List<String> entries = new ArrayList<>();
        for (String token : parts[1].trim().split("\\s+")) {
            if (!token.isBlank()) entries.add(token);
        }
        List<Integer> numericModes = ThinkingControlConfig.numericModesFromArguments(entries);
        String nextModes = ThinkingControlConfig.formatModes(numericModes);
        Set<String> errors = new LinkedHashSet<>();
        errors.addAll(ThinkingControlConfig.validateModeNumbers(numericModes));
        errors.addAll(ThinkingControlConfig.validateControls(nextModes, session.getThinkingControls()));
        if (!errors.isEmpty()) {
            for (String error : errors) System.out.println("(invalid thinking configuration: " + error + ")");
            System.out.println();
            return;
        }

        ThinkingConfig thinking = new ThinkingConfig();
        for (String entry : entries) {
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                if (entry.matches("[1-5](,[1-5])*")) {
                    thinking.addMode(entry, null, null);
                } else {
                    System.out.println("(skip: '" + entry + "', expected key=value)");
                }
                continue;
            }
            String key = entry.substring(0, idx).trim();
            String value = entry.substring(idx + 1);
            if (key.matches("[1-5](,[1-5])*")) {
                thinking.addMode(key, value, value);
            } else {
                thinking.add(key, value);
            }
        }
        session.setThinking(thinking);
        session.setThinkingModes(nextModes);
        agent.setLlm(session.rebuildLlm());
        System.out.println("thinking now: " + session.getThinking() + "\n");
    }

    // -------- helpers --------

    private List<AgentApp> discoverAgents(Config cfg, String agentsDirOverride) {
        Path dir = resolveAgentsDir(cfg, agentsDirOverride);
        List<AgentApp> apps = new ArrayList<>();

        if (Files.isDirectory(dir)) {
            List<Path> files = AgentLoader.listYamlFiles(dir);
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path p : files) {
                try {
                    apps.add(AgentLoader.loadFile(p));
                } catch (Exception e) {
                    System.err.println("Skip " + p + ": " + e.getMessage());
                }
            }
        }

        for (String builtin : new String[]{
                "/agents/coder.yaml",
                "/agents/researcher.yaml",
                "/agents/shell-helper.yaml"}) {
            try {
                AgentApp a = AgentLoader.loadResource(builtin);
                if (apps.stream().noneMatch(x -> x.getName().equals(a.getName()))) {
                    apps.add(a);
                }
            } catch (Exception ignored) {
                // Built-in resources are best-effort; missing ones simply aren't listed.
            }
        }
        return apps;
    }

    private AgentApp loadAgent(Config cfg, String agentName, String agentsDirOverride) {
        Path direct = Path.of(agentName);
        if (Files.exists(direct) && Files.isRegularFile(direct)) {
            return AgentLoader.loadFile(direct);
        }

        Path dir = resolveAgentsDir(cfg, agentsDirOverride);
        for (Path p : AgentLoader.listYamlFiles(dir)) {
            String base = p.getFileName().toString().replaceAll("\\.(yaml|yml)$", "");
            if (base.equals(agentName)) {
                return AgentLoader.loadFile(p);
            }
        }

        for (String builtin : new String[]{
                "/agents/coder.yaml",
                "/agents/researcher.yaml",
                "/agents/shell-helper.yaml"}) {
            try {
                AgentApp a = AgentLoader.loadResource(builtin);
                if (agentName.equals(a.getName())) return a;
            } catch (Exception ignored) {
                // Continue searching other built-in agents.
            }
        }

        die("agent not found: " + agentName + " (use `aiclaw list` to see available agents)");
        return null;
    }

    private Path resolveAgentsDir(Config cfg, String override) {
        if (override != null) return Path.of(override);
        return cfg.getAgentsDir();
    }

    /**
     * Returns the first non-null, non-blank value among {@code values}. Used to implement the
     * "CLI override > agent.yaml > cfg" precedence chain for model / url / key.
     */
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n - 1) + "...";
    }

    private static void die(String msg) {
        System.err.println("Error: " + msg);
        System.err.println("Run `aiclaw --help` for usage.");
        System.exit(2);
    }

    private static void printHelp() {
        String text = String.join("\n",
                "aiclaw - AI Agent command-line tool",
                "",
                "SYNOPSIS",
                "  aiclaw [command] [options]",
                "  aiclaw <agent>                  # start an agent REPL",
                "  aiclaw <agent> <message...>     # ask once and exit",
                "  aiclaw <file.yaml>              # load an agent YAML and start its REPL",
                "",
                "COMMANDS",
                "  list, ls      List available agents",
                "  show <agent>  Show an agent configuration and system prompt",
                "  tools         List built-in tools",
                "  run <agent>   Start an interactive agent session",
                "  ask <agent> <message>  Ask once without entering the REPL",
                "  setup         Run the interactive configuration wizard",
                "  config [show|path]  Show effective configuration or file locations",
                "  profile ...   Manage connection profiles",
                "  help          Show this help",
                "",
                "OPTIONS",
                "  --base-url <url>          LLM API base URL",
                "  --api-key <key>           API key",
                "  --model <name>            Override the agent YAML model",
                "  --style <key>             openai-general | gemini-general | claude-general",
                "  --agents-dir <dir>        Agent YAML directory",
                "  --thinking-arg k=v        Pass through a thinking parameter (repeatable)",
                "  --thinking-modes <modes>  Thinking mode numbers, e.g. 1,5",
                "  --thinking-level <value>  Legacy thinking effort override",
                "  --thinking-budget <value> Legacy thinking budget override",
                "  --no-<tool>               Disable a tool, e.g. --no-shell",
                "  -i, --interactive         Force interactive mode",
                "  --no-input                Skip interactive setup prompts",
                "  -h, --help                Show this help",
                "  -v, --version             Show version",
                "",
                "REPL COMMANDS",
                "  /help                Show REPL help",
                "  /state               Show session state",
                "  /tools               List enabled and disabled tools",
                "  /tool enable <name>  Enable a tool",
                "  /tool disable <name> Disable a tool",
                "  /thinking [k=v ...]  Show or set thinking parameters",
                "  /reasoning           Reprint reasoning from the previous reply",
                "  /history             Show history size",
                "  /reset               Clear history while keeping the system prompt",
                "  /exit, /quit         Exit",
                "  Ctrl+A               Adjust profile-defined thinking controls",
                "  Ctrl+Y               Start a new in-memory conversation",
                "  Ctrl+F               Choose a model from /models",
                "  @/abs/path.png       Attach an image in chat mode",
                "",
                "EXAMPLES",
                "  aiclaw list",
                "  aiclaw coder",
                "  aiclaw ask coder \"write a Hello World program\"",
                "  aiclaw --thinking-arg reasoning_effort=high ask researcher \"research Java 21\"");
        System.out.println(Ansi.bold(text));
    }

    private static void printQuickStart() {
        String text = String.join("\n",
                "aiclaw is not configured yet.",
                "",
                "Run `aiclaw setup` for guided setup, or create a profile manually:",
                "  mkdir -p ~/.aiclaw/profiles",
                "  aiclaw profile add",
                "",
                "Environment-variable alternative:",
                "  export AICLAW_API_KEY=sk-xxxxxxxx",
                "  export AICLAW_BASE_URL=https://api.openai.com/v1",
                "  export AICLAW_MODEL=<model-id>",
                "",
                "After configuration: aiclaw coder");
        System.out.println(Ansi.info(text));
    }

    private static void printReplHelp() {
        String text = String.join("\n",
                "REPL COMMANDS:",
                "  /help                Show this help",
                "  /state               Show current session state",
                "  /exit, /quit         Exit",
                "  /reset               Clear history; keep the system prompt",
                "  /tools               List enabled and disabled tools",
                "  /tool                List all tool switches",
                "  /tool enable <name>  Enable a tool",
                "  /tool disable <name> Disable a tool",
                "  /thinking [k=v ...]  Show or set thinking parameters",
                "  /reasoning           Reprint reasoning returned for the previous reply",
                "  /history             Show message count",
                "  Ctrl+A               Change profile-defined thinking controls",
                "  Ctrl+Y               Start a new in-memory conversation",
                "  Ctrl+F               Choose a model from the active endpoint",
                "  @/abs/path.png       Attach an image in chat mode",
                "",
                "CHAT / COMMAND MODE:",
                "  Chat mode is the default; ordinary text is sent to the model.",
                "  Type esc to toggle command mode. Unknown command-mode input is never sent to the model.",
                "  eft-<level>          Set reasoning effort",
                "  rty-<budget>         Set thinking budget",
                "  tk:<modes>:<lvl>:<b> Set thinking modes, effort, and budget",
                "  sys:\"<text>\"         Replace the system prompt",
                "  modst-<model>        Switch model ID",
                "  sklp:<key>           Switch API key",
                "  base-ur-l:<url>      Switch base URL",
                "  style:<key>          Switch request style");
        System.out.println(Ansi.bold(text));
        System.out.println();
    }
}