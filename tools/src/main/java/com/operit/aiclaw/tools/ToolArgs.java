package com.operit.aiclaw.tools;

import java.util.Map;

/** Small, strict argument helpers shared by tools. */
final class ToolArgs {
    private ToolArgs() {}

    static String requiredString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolException(name + " is required");
        }
        return text;
    }

    static String string(Map<String, Object> args, String name, String fallback) {
        Object value = args.get(name);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    static int boundedInt(Map<String, Object> args, String name, int fallback, int min, int max) {
        Object value = args.get(name);
        int result = fallback;
        if (value instanceof Number number) {
            result = number.intValue();
        } else if (value instanceof String text) {
            try { result = Integer.parseInt(text.trim()); } catch (NumberFormatException ignored) { }
        }
        return Math.max(min, Math.min(max, result));
    }

    static boolean bool(Map<String, Object> args, String name, boolean fallback) {
        Object value = args.get(name);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return fallback;
    }
}
