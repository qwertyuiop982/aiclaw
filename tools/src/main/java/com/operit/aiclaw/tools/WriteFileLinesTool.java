package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * File write tool - whole-file overwrite, append, or insert at a specific line number.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code "overwrite"} - replace the entire file with {@code content} (default).</li>
 *   <li>{@code "append"}    - append {@code content} to the end of the file.</li>
 *   <li>{@code "insert"}    - insert {@code content} at line {@code line_number}; the original
 *       line and everything after shift down.</li>
 * </ul>
 *
 * <p>Boundaries:</p>
 * <ul>
 *   <li>Missing parent directories are created automatically.</li>
 *   <li>No automatic backup is performed.</li>
 *   <li>Files larger than 5 MB trigger a warning in the description (the limit is enforced by
 *       the {@code read_file_lines} tool, not here).</li>
 * </ul>
 */
public class WriteFileLinesTool implements Tool {

    @Override
    public String name() { return "write_file_lines"; }

    @Override
    public String description() {
        return "Writes a local text file in one of three ways: "
                + "action='overwrite' replaces the file with content (default); "
                + "action='append' appends content to the end of the file; "
                + "action='insert' inserts content at line number N (1-based); the original line "
                + "and everything after shift down. Before overwrite/insert, please confirm the "
                + "operation and use read_file_lines first if you need to verify line numbers.";
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
        JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        content.addProperty("description", "Text content to write");
        props.add("content", content);
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: overwrite | append | insert (default overwrite)");
        props.add("action", action);
        JsonObject lineNumber = new JsonObject();
        lineNumber.addProperty("type", "integer");
        lineNumber.addProperty("description", "Insert position (1-based); only used by action=insert; line_number=0 means insert as the first line");
        props.add("line_number", lineNumber);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"path\",\"content\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.requiredString(arguments, "path");
        Object rawContent = arguments.get("content");
        if (!(rawContent instanceof String content)) throw new ToolException("content is required");
        String action = ToolArgs.string(arguments, "action", "overwrite").toLowerCase();
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            switch (action) {
                case "append": {
                    Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return "appended " + content.length() + " chars to " + path;
                }
                case "insert": {
                    int lineNumber = 0;
                    Object lo = arguments.get("line_number");
                    if (lo instanceof Number) lineNumber = ((Number) lo).intValue();
                    if (lineNumber < 0) lineNumber = 0;

                    StringBuilder existing = new StringBuilder();
                    if (Files.exists(path)) {
                        existing.append(Files.readString(path, StandardCharsets.UTF_8));
                    }
                    if (existing.length() > 0 && existing.charAt(existing.length() - 1) != '\n') {
                        existing.append('\n');
                    }
                    String[] lines = existing.toString().split("\\r?\\n", -1);
                    StringBuilder result = new StringBuilder();
                    int insertAt = Math.max(0, Math.min(lineNumber == 0 ? 0 : lineNumber - 1, lines.length));
                    for (int i = 0; i < insertAt; i++) result.append(lines[i]).append('\n');
                    result.append(content);
                    if (!content.endsWith("\n")) result.append('\n');
                    for (int i = insertAt; i < lines.length; i++) result.append(lines[i]).append('\n');
                    // Trim trailing blank lines but keep a single newline.
                    while (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
                        result.deleteCharAt(result.length() - 1);
                    }
                    result.append('\n');
                    Files.writeString(path, result.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return "inserted " + content.length() + " chars at line " + lineNumber + " of " + path;
                }
                case "overwrite":
                default: {
                    Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return "wrote " + content.length() + " chars to " + path;
                }
            }
        } catch (IOException e) {
            throw new ToolException("write_file_lines failed: " + e.getMessage(), e);
        }
    }
}