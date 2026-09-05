package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads the entire contents of a local text file. */
public class ReadFileTool implements Tool {

    @Override
    public String name() { return "read_file"; }

    @Override
    public String description() { return "Reads the entire contents of a local text file."; }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "File path (absolute or relative to the current working directory)");
        props.add("path", path);
        JsonObject max = new JsonObject();
        max.addProperty("type", "integer");
        max.addProperty("description", "Maximum number of characters to return (default 8000)");
        props.add("max_chars", max);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"path\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.requiredString(arguments, "path");
        int max = ToolArgs.boundedInt(arguments, "max_chars", 8000, 1, 1_000_000);
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.length() > max) {
                return content.substring(0, max) + "\n\n... (truncated, total " + content.length() + " chars)";
            }
            return content;
        } catch (IOException e) {
            throw new ToolException("read_file failed: " + e.getMessage(), e);
        }
    }
}