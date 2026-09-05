package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File / directory delete tool.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code "file"} - delete a single file (default).</li>
 *   <li>{@code "dir"}  - delete a directory. With {@code recursive=false} the directory must be empty.</li>
 * </ul>
 *
 * <p>Boundaries:</p>
 * <ul>
 *   <li>Files do not go through a recycle bin: deletion is irreversible.</li>
 *   <li>Symbolic links are never followed.</li>
 * </ul>
 */
public class DeleteFileTool implements Tool {

    @Override
    public String name() { return "delete_file"; }

    @Override
    public String description() {
        return "Deletes a local file or directory. Deletion is irreversible - please use this carefully. "
                + "action='file' deletes a single file (default); action='dir' deletes a directory, "
                + "which must be empty when recursive=false (default). "
                + "Symbolic links are never followed into dangerous system paths like /etc, /, /sys, /proc.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "File or directory path to delete");
        props.add("path", path);
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: file | dir (default file)");
        props.add("action", action);
        JsonObject recursive = new JsonObject();
        recursive.addProperty("type", "boolean");
        recursive.addProperty("description", "Recurse into sub-directories when deleting a directory (default false; non-empty directories fail without this)");
        props.add("recursive", recursive);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"path\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = ToolArgs.requiredString(arguments, "path");
        Path path = PathPolicy.normalize(pathStr);
        PathPolicy.rejectDangerous(path);
        if (PathPolicy.isSymlink(path)) {
            throw new ToolException("refusing to delete symbolic links");
        }

        String action = ToolArgs.string(arguments, "action", "file").toLowerCase();
        boolean recursive = ToolArgs.bool(arguments, "recursive", false);
        if (!action.equals("file") && !action.equals("dir")) throw new ToolException("action must be file or dir");

        try {
            if (!Files.exists(path)) {
                throw new ToolException("path does not exist: " + path);
            }
            if ("dir".equals(action)) {
                if (recursive) {
                    List<String> failures = new ArrayList<>();
                    try (Stream<Path> walk = Files.walk(path)) {
                        walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(p -> {
                                if (Files.isSymbolicLink(p)) {
                                    failures.add(p + " (symbolic link)");
                                    return;
                                }
                                try { Files.deleteIfExists(p); }
                                catch (IOException e) { failures.add(p + " (" + e.getMessage() + ")"); }
                            });
                    }
                    if (!failures.isEmpty()) throw new ToolException("delete incomplete: " + String.join(", ", failures));
                    return "deleted directory recursively: " + path;
                } else {
                    try (Stream<Path> stream = Files.list(path)) {
                        if (stream.findAny().isPresent()) {
                            throw new ToolException("directory not empty: " + path);
                        }
                    }
                    Files.delete(path);
                    return "deleted empty directory: " + path;
                }
            } else {
                Files.delete(path);
                return "deleted file: " + path;
            }
        } catch (IOException e) {
            throw new ToolException("delete_file failed: " + e.getMessage(), e);
        }
    }
}