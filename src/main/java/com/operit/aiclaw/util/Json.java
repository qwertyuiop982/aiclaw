package com.operit.aiclaw.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * JSON helpers that wrap Gson with a single, project-wide configuration.
 *
 * <p>All callers share one {@link Gson} instance configured for pretty-printing and
 * non-HTML-escaped output; this keeps logs readable and avoids leaking {@code "} / {@code &gt;}
 * into request bodies.</p>
 */
public final class Json {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private Json() {}

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static JsonObject parse(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    public static Gson gson() {
        return GSON;
    }
}