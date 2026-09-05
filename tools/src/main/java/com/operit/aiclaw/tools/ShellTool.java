package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes a single shell command. Note: this tool has real safety implications; agents should
 * decide carefully whether to expose it via their YAML whitelist.
 */
public class ShellTool implements Tool {

    private static final int OUTPUT_LIMIT = 8192;

    @Override
    public String name() { return "shell"; }

    @Override
    public String description() {
        return "Runs a single shell command locally (bash -c); returns stdout (truncated to 8 KB) and the exit code.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description", "Shell command string to execute");
        props.add("command", command);
        JsonObject timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("description", "Timeout in seconds (default 30)");
        props.add("timeout_seconds", timeout);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"command\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = ToolArgs.requiredString(arguments, "command");
        int timeout = ToolArgs.boundedInt(arguments, "timeout_seconds", 30, 1, 300);

        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return "[timeout after " + timeout + "s]";
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > OUTPUT_LIMIT) break;
                }
            }
            String text = out.toString();
            if (text.length() > OUTPUT_LIMIT) {
                text = text.substring(0, OUTPUT_LIMIT) + "\n... (truncated)";
            }
            return "[exit=" + p.exitValue() + "]\n" + text;
        } catch (Exception e) {
            throw new ToolException("shell failed: " + e.getMessage(), e);
        }
    }
}