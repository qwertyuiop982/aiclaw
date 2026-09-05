package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File move / copy tool - combined as a single action-based tool.
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code "move"} - move {@code src} to {@code dst} (controlled by {@code overwrite}).</li>
 *   <li>{@code "copy"} - copy {@code src} to {@code dst} (controlled by {@code overwrite}).</li>
 * </ul>
 *
 * <p>Boundaries:</p>
 * <ul>
 *   <li>Cross-directory moves and copies create the destination's parent directories on demand.</li>
 *   <li>After a move the source path no longer exists; after a copy it does.</li>
 *   <li>Cross-device moves fall back to copy + delete: if any source file cannot be cleaned up,
 *       an exception is raised.</li>
 * </ul>
 */
public class MoveFileTool implements Tool {

    @Override
    public String name() { return "move_file"; }

    @Override
    public String description() {
        return "Moves or copies a local file or directory. "
                + "action='move' relocates src -> dst; action='copy' duplicates src -> dst. "
                + "If dst already exists, overwrite=true will replace it (default false). "
                + "Missing parent directories of dst are created automatically.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: move | copy (default move)");
        props.add("action", action);
        JsonObject src = new JsonObject();
        src.addProperty("type", "string");
        src.addProperty("description", "Source path");
        props.add("src", src);
        JsonObject dst = new JsonObject();
        dst.addProperty("type", "string");
        dst.addProperty("description", "Destination path");
        props.add("dst", dst);
        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("type", "boolean");
        overwrite.addProperty("description", "Replace an existing destination (default false)");
        props.add("overwrite", overwrite);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"src\",\"dst\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String srcStr = ToolArgs.requiredString(arguments, "src");
        String dstStr = ToolArgs.requiredString(arguments, "dst");
        String action = ToolArgs.string(arguments, "action", "move").toLowerCase();
        boolean overwrite = ToolArgs.bool(arguments, "overwrite", false);

        if (!action.equals("move") && !action.equals("copy")) throw new ToolException("action must be move or copy");
        Path src = PathPolicy.normalize(srcStr);
        Path dst = PathPolicy.normalize(dstStr);
        PathPolicy.rejectDangerous(src);
        PathPolicy.rejectDangerous(dst);
        PathPolicy.rejectDirectoryIntoChild(src, dst);

        try {
            if (!Files.exists(src)) {
                throw new ToolException("src does not exist: " + src);
            }
            Path parent = dst.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            if ("copy".equals(action)) {
                if (Files.isDirectory(src)) {
                    copyDir(src, dst, overwrite);
                    return "copied directory " + src + " -> " + dst;
                }
                Files.copy(src, dst,
                        overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                                  : new StandardCopyOption[0]);
                return "copied " + src + " -> " + dst;
            } else {
                // move
                if (Files.isDirectory(src)) {
                    moveDir(src, dst, overwrite);
                    return "moved directory " + src + " -> " + dst;
                }
                Files.move(src, dst,
                        overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                                  : new StandardCopyOption[0]);
                return "moved " + src + " -> " + dst;
            }
        } catch (IOException e) {
            throw new ToolException("move_file failed: " + e.getMessage(), e);
        }
    }

    private void copyDir(Path src, Path dst, boolean overwrite) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.walk(src)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path p = iterator.next();
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isSymbolicLink(p)) throw new IOException("symbolic links are not supported: " + p);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Path par = target.getParent();
                    if (par != null) Files.createDirectories(par);
                    Files.copy(p, target,
                            overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                                      : new StandardCopyOption[0]);
                }
            }
        }
    }

    private void moveDir(Path src, Path dst, boolean overwrite) throws IOException {
        try {
            Files.move(src, dst, overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new StandardCopyOption[0]);
            return;
        } catch (java.nio.file.FileSystemException ignored) {
            // Cross-device fallback below.
        }
        copyDir(src, dst, overwrite);
        List<String> failures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(src)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { failures.add(p.toString()); }
            });
        }
        if (!failures.isEmpty()) throw new IOException("source cleanup incomplete: " + String.join(", ", failures));
    }
}