package com.operit.aiclaw.agent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads an {@link AgentApp} from a YAML source. Supports:
 * <ul>
 *   <li>A single yaml file ({@link Path}).</li>
 *   <li>Every {@code *.yaml} / {@code *.yml} in a directory.</li>
 *   <li>A classpath resource, e.g. {@code /agents/coder.yaml} (used as a built-in fallback).</li>
 * </ul>
 */
public class AgentLoader {

    /** Load an {@link AgentApp} from a yaml file on disk. */
    public static AgentApp loadFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            AgentApp app = parse(in);
            if (app.getName() == null || app.getName().isBlank()) {
                // Fall back to the filename (without extension) when the yaml omits a name.
                String fname = file.getFileName().toString();
                app.setName(fname.replaceAll("\\.(yaml|yml)$", ""));
            }
            return app;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load agent from " + file + ": " + e.getMessage(), e);
        }
    }

    /** Load an {@link AgentApp} from a classpath resource, e.g. {@code /agents/coder.yaml}. */
    public static AgentApp loadResource(String resourcePath) {
        InputStream in = AgentLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("classpath resource not found: " + resourcePath);
        }
        try (InputStream close = in) {
            return parse(close);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load agent " + resourcePath, e);
        }
    }

    /** List every {@code *.yaml} / {@code *.yml} in a directory (not recursive). */
    public static List<Path> listYamlFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{yaml,yml}")) {
            for (Path p : stream) out.add(p);
        } catch (IOException ignored) {}
        return out;
    }

    @SuppressWarnings("unchecked")
    private static AgentApp parse(InputStream in) {
        Yaml yaml = new Yaml();
        Object obj = yaml.load(in);
        if (!(obj instanceof Map)) {
            throw new IllegalArgumentException("Agent yaml root must be a mapping");
        }
        Map<String, Object> root = (Map<String, Object>) obj;
        AgentApp app = new AgentApp();
        app.setName((String) root.get("name"));
        app.setDescription((String) root.get("description"));
        app.setModel((String) root.get("model"));
        app.setSystemPrompt((String) root.get("system_prompt"));

        Object tools = root.get("tools");
        if (tools instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object t : (List<?>) tools) {
                if (t != null) list.add(t.toString().trim());
            }
            app.setTools(list);
        }

        Object opts = root.get("options");
        if (opts instanceof Map) {
            app.setOptions(new LinkedHashMap<>((Map<String, Object>) opts));
        }

        app.setGreeting((String) root.get("greeting"));
        return app;
    }
}