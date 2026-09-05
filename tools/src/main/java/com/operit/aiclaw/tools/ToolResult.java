package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;

/** Structured, model-facing result while preserving the legacy String tool API. */
public record ToolResult(
        ToolStatus status,
        String summary,
        String output,
        boolean truncated,
        long durationMs
) {
    public ToolResult {
        status = status == null ? ToolStatus.ERROR : status;
        summary = summary == null ? "" : summary;
        output = output == null ? "" : output;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("status", status.name().toLowerCase());
        json.addProperty("summary", summary);
        json.addProperty("output", output);
        json.addProperty("truncated", truncated);
        json.addProperty("duration_ms", durationMs);
        return json;
    }

    public String forModel() { return toJson().toString(); }
}