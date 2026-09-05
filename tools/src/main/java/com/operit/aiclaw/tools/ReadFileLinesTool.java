package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * File read tool - reads the entire file or a specific line range.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code "read"} - reads the entire file (default; optional {@code start_line}/{@code end_line}
 *       to clip a slice).</li>
 *   <li>{@code "head"} - reads the first N lines (default 50).</li>
 *   <li>{@code "tail"} - reads the last N lines (default 50).</li>
 * </ul>
 *
 * <p>Boundaries:</p>
 * <ul>
 *   <li>Each call returns at most 8000 characters; longer output is truncated and labelled.</li>
 *   <li>Line numbers are 1-based.</li>
 *   <li>Missing files raise a {@link Tool.ToolException}.</li>
 * </ul>
 */
public class ReadFileLinesTool implements Tool {

    private static final int RESULT_LIMIT = 8000;
    private static final int DEFAULT_HEAD_TAIL = 50;

    @Override
    public String name() { return "read_file_lines"; }

    @Override
    public String description() {
        return "Reads a local text file, optionally constrained to a line-number range: "
                + "action='read' reads the whole file or the [start_line, end_line] interval; "
                + "action='head' reads the first N lines (default 50); "
                + "action='tail' reads the last N lines (default 50). "
                + "Line numbers are 1-based. The returned string is prefixed with `L1|L2|...` "
                + "line markers so subsequent write_file_lines calls can reference the same positions.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "File path");
        props.add("path", path);
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: read | head | tail (default read)");
        props.add("action", action);
        JsonObject start = new JsonObject();
        start.addProperty("type", "integer");
        start.addProperty("description", "Starting line number (1-based, inclusive); only used by action=read");
        props.add("start_line", start);
        JsonObject end = new JsonObject();
        end.addProperty("type", "integer");
        end.addProperty("description", "Ending line number (1-based, inclusive); only used by action=read; 0 means end of file");
        props.add("end_line", end);
        JsonObject n = new JsonObject();
        n.addProperty("type", "integer");
        n.addProperty("description", "Number of lines for head/tail (default 50)");
        props.add("n", n);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"path\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.requiredString(arguments, "path");
        String action = ToolArgs.string(arguments, "action", "read").toLowerCase();
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);

        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("read_file_lines failed: " + e.getMessage(), e);
        }

        String[] lines = content.split("\\r?\\n", -1);

        switch (action) {
            case "head": {
                int n = intArg(arguments.get("n"), DEFAULT_HEAD_TAIL);
                int take = Math.min(n, lines.length);
                return formatLines(lines, 1, take);
            }
            case "tail": {
                int n = intArg(arguments.get("n"), DEFAULT_HEAD_TAIL);
                int from = Math.max(1, lines.length - n + 1);
                return formatLines(lines, from, lines.length);
            }
            case "read":
            default: {
                int start = intArg(arguments.get("start_line"), 1);
                int end = intArg(arguments.get("end_line"), 0);
                if (start < 1) start = 1;
                if (end <= 0 || end > lines.length) end = lines.length;
                if (start > end) {
                    return "(start_line > end_line, empty range)";
                }
                return formatLines(lines, start, end);
            }
        }
    }

    private static int intArg(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static String formatLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("[lines ").append(from).append('-').append(to).append(" of ").append(lines.length).append("]\n");
        int width = String.valueOf(to).length();
        for (int i = from; i <= to; i++) {
            sb.append(String.format("L%0" + width + "d| %s%n", i, lines[i - 1]));
        }
        String text = sb.toString();
        if (text.length() > RESULT_LIMIT) {
            text = text.substring(0, RESULT_LIMIT) + "\n...(truncated)";
        }
        return text;
    }
}