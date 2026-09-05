package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Writes (or appends) a text payload to a local file. */
public class WriteFileTool implements Tool {

    @Override
    public String name() { return "write_file"; }

    @Override
    public String description() {
        return "Writes text to a local file. Overwrites an existing file by default; pass append=true to append instead.";
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
        content.addProperty("description", "Content to write");
        props.add("content", content);
        JsonObject append = new JsonObject();
        append.addProperty("type", "boolean");
        append.addProperty("description", "Append to the end instead of overwriting (default false)");
        props.add("append", append);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"path\",\"content\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.requiredString(arguments, "path");
        Object rawContent = arguments.get("content");
        if (!(rawContent instanceof String content)) throw new ToolException("content is required");
        boolean append = ToolArgs.bool(arguments, "append", false);
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return "wrote " + content.length() + " chars to " + path;
        } catch (IOException e) {
            throw new ToolException("write_file failed: " + e.getMessage(), e);
        }
    }
}