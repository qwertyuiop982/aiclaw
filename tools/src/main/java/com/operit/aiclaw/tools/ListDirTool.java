package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Lists the files and sub-directories inside a directory. */
public class ListDirTool implements Tool {

    @Override
    public String name() { return "list_dir"; }

    @Override
    public String description() { return "Lists the files and sub-directories inside the given directory."; }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Directory path; defaults to the current working directory");
        props.add("path", path);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.string(arguments, "path", ".");
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);
        if (!Files.isDirectory(path)) {
            throw new ToolException("not a directory: " + path);
        }
        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .map(p -> {
                        String type = Files.isDirectory(p) ? "dir  " : "file";
                        return type + "  " + p.getFileName();
                    })
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new ToolException("list_dir failed: " + e.getMessage(), e);
        }
    }
}