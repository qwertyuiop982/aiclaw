package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/** Echoes back whatever text it is given; primarily used as a smoke test for tool wiring. */
public class EchoTool implements Tool {

    @Override
    public String name() { return "echo"; }

    @Override
    public String description() { return "Echoes the supplied text; useful for verifying tool wiring."; }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "Text to echo back");
        props.add("text", text);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"text\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return String.valueOf(arguments.getOrDefault("text", ""));
    }
}