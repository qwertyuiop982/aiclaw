package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/** Persistent terminal-session tool. It manages stateful bash sessions; it is not a sandbox. */
public final class TerminalTool implements Tool, AutoCloseable {
    private final TerminalManager manager;
    private final String defaultCwd;

    public TerminalTool() {
        this(new TerminalManager(), System.getProperty("user.dir"));
    }

    public TerminalTool(TerminalManager manager, String defaultCwd) {
        this.manager = manager == null ? new TerminalManager() : manager;
        this.defaultCwd = defaultCwd;
    }

    public TerminalManager manager() { return manager; }

    @Override public String name() { return "terminal"; }

    @Override public String description() {
        return "Manages an independent persistent local bash terminal session (not a sandbox). "
                + "Actions: create, exec, input, screen, wait, close, list. Sessions keep cwd and environment.";
    }

    @Override public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("action", string("create|exec|input|screen|transcript|wait|close|list"));
        props.add("terminal_id", string("Existing terminal session id"));
        props.add("cwd", string("Working directory for a new session; defaults to the process directory"));
        props.add("command", string("Command to execute"));
        props.add("input", string("Text sent to the terminal process"));
        props.add("enter", bool("Append Enter after input (default true)"));
        props.add("timeout_seconds", integer("Timeout in seconds (default 30)"));
        props.add("max_chars", integer("Maximum returned screen/output characters (default 12000)"));
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"action\"]"));
        return schema;
    }

    @Override public String execute(Map<String, Object> arguments) {
        String action = ToolArgs.requiredString(arguments, "action").toLowerCase();
        int timeout = ToolArgs.boundedInt(arguments, "timeout_seconds", 30, 1, 3600);
        int maxChars = ToolArgs.boundedInt(arguments, "max_chars", 12000, 100, 100000);
        return switch (action) {
            case "create" -> "[terminal_session][sandbox=false]\nterminal_id="
                    + manager.create(ToolArgs.string(arguments, "cwd", defaultCwd));
            case "exec" -> manager.execute(
                    ToolArgs.requiredString(arguments, "terminal_id"),
                    ToolArgs.requiredString(arguments, "command"), timeout);
            case "input" -> manager.input(
                    ToolArgs.requiredString(arguments, "terminal_id"),
                    ToolArgs.string(arguments, "input", ""),
                    ToolArgs.bool(arguments, "enter", true));
            case "screen" -> manager.screen(ToolArgs.requiredString(arguments, "terminal_id"), maxChars);
            case "transcript" -> "transcript=" + manager.transcriptPath(ToolArgs.requiredString(arguments, "terminal_id"));
            case "wait" -> manager.waitFor(ToolArgs.requiredString(arguments, "terminal_id"), timeout);
            case "close" -> manager.close(ToolArgs.requiredString(arguments, "terminal_id"));
            case "list" -> "terminals=" + manager.list();
            default -> throw new ToolException("unknown terminal action: " + action);
        };
    }
    @Override public void close() { manager.close(); }

    @Override public String toString() { return "TerminalTool{defaultCwd='" + defaultCwd + "'}"; }


    private static JsonObject string(String description) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        value.addProperty("description", description);
        return value;
    }

    private static JsonObject integer(String description) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "integer");
        value.addProperty("description", description);
        return value;
    }

    private static JsonObject bool(String description) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "boolean");
        value.addProperty("description", description);
        return value;
    }
}
