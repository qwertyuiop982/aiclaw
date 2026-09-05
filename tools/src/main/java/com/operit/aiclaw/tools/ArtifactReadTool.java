package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
// ArtifactStore is provided by this module to keep dependency direction one-way.

import java.util.Map;

/** Reads a previously stored large tool-output artifact. */
public final class ArtifactReadTool implements Tool {
    private final ArtifactStore store;

    public ArtifactReadTool(ArtifactStore store) { this.store = store; }

    @Override public String name() { return "artifact_read"; }
    @Override public String description() { return "Reads more content from a large tool-output artifact by id."; }

    @Override public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject(); id.addProperty("type", "string"); props.add("artifact_id", id);
        JsonObject max = new JsonObject(); max.addProperty("type", "integer"); props.add("max_chars", max);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"artifact_id\"]"));
        return schema;
    }

    @Override public String execute(Map<String, Object> arguments) {
        if (store == null) throw new ToolException("artifact store is unavailable");
        String id = ToolArgs.requiredString(arguments, "artifact_id");
        int max = ToolArgs.boundedInt(arguments, "max_chars", 12000, 100, 100000);
        try { return store.read(id, max); }
        catch (Exception e) { throw new ToolException("artifact_read failed: " + e.getMessage(), e); }
    }
}