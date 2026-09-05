package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry that manages every available {@link Tool}.
 *
 * <p>Usage:
 * <pre>
 *   ToolRegistry r = new ToolRegistry();
 *   r.register(new ReadFileTool());
 *   ...
 *   Tool t = r.get("read_file");
 *   String result = t.execute(Map.of("path", "/tmp/x.txt"));
 * </pre>
 *
 * <p>Enable/disable mechanism:</p>
 * <ul>
 *   <li>{@link #enabled(String)} - whether a tool is exposed to the LLM.</li>
 *   <li>{@link #enable(String)} / {@link #disable(String)} - runtime toggles.</li>
 *   <li>{@link #exportOpenAiTools()} - exports only the enabled subset.</li>
 *   <li>{@link #subset(Set)} - builds a registry limited to a whitelist (agent configuration).</li>
 * </ul>
 */
public class ToolRegistry implements AutoCloseable {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> disabled = new LinkedHashSet<>();
    /** Hard-disabled tools cannot be re-enabled by a runtime/REPL toggle. */
    private final Set<String> locked = new LinkedHashSet<>();
    public ToolRegistry register(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("tool is null");
        if (tools.put(tool.name(), tool) != null) {
            throw new IllegalStateException("duplicate tool: " + tool.name());
        }
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** Whether the named tool is enabled (present and not in the disabled set). */
    public boolean enabled(String name) {
        return tools.containsKey(name) && !disabled.contains(name);
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public Collection<Tool> all() {
        return tools.values();
    }

    /** Returns only the currently enabled tools, preserving registration order. */
    public List<Tool> enabledList() {
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (!disabled.contains(t.name())) out.add(t);
        }
        return out;
    }

    public ToolRegistry enable(String name) {
        if (!locked.contains(name)) disabled.remove(name);
        return this;
    }

    public ToolRegistry disable(String name) {
        if (tools.containsKey(name)) disabled.add(name);
        return this;
    }

    /** Permanently disables a tool for this registry lifetime; runtime enable cannot override it. */
    public ToolRegistry lock(String name) {
        if (tools.containsKey(name)) {
            locked.add(name);
            disabled.add(name);
        }
        return this;
    }

    public boolean locked(String name) {
        return locked.contains(name);
    }

    /** Exports the enabled tools in the OpenAI tool-call format. */
    public List<Map<String, Object>> exportOpenAiTools() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (disabled.contains(t.name())) continue;
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", t.toToolSchema());

            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            list.add(wrapper);
        }
        return list;
    }

    /** Default registry: every built-in tool shipped with aiclaw. */
    public static ToolRegistry defaults() {
        return defaults(null);
    }

    /** Creates the default registry and optionally exposes an artifact reader. */
    public static ToolRegistry defaults(ArtifactStore artifactStore) {
        ToolRegistry r = new ToolRegistry();
        r.register(new EchoTool());
        r.register(new ReadFileTool());
        r.register(new WriteFileTool());
        r.register(new ListDirTool());
        r.register(new ShellTool());
        r.register(new TerminalTool());
        r.register(new HttpGetTool());
        r.register(new BingSearchTool());
        r.register(new FetchUrlTool());
        r.register(new ReadFileLinesTool());
        r.register(new WriteFileLinesTool());
        r.register(new MoveFileTool());
        r.register(new DeleteFileTool());
        if (artifactStore != null) r.register(new ArtifactReadTool(artifactStore));
        return r;
    }

    @Override
    public void close() {
        for (Tool tool : tools.values()) {
            if (tool instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Returns a registry restricted to the supplied whitelist. Names in the whitelist that do
     * not correspond to a known tool are silently skipped; unknown tools already produce a
     * warning at the CLI layer.
     */
    public ToolRegistry subset(Set<String> whitelist) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools.values()) {
            if (whitelist.contains(t.name())) {
                r.register(t);
            }
        }
        return r;
    }
}