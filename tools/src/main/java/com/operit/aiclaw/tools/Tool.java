package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * A tool that an Agent can call.
 *
 * <p>An implementation must:</p>
 * <ol>
 *   <li>Expose an OpenAI-style JSON Schema ({@link #toToolSchema()}).</li>
 *   <li>Actually perform the call and return a string result ({@link #execute(Map)}).</li>
 * </ol>
 */
public interface Tool {

    /** Tool name, used by the LLM to reference this tool. */
    String name();

    /** Tool description, surfaced to the LLM as documentation. */
    String description();

    /**
     * JSON Schema for the tool's parameters, in OpenAI tool-call format. Example:
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "path": { "type": "string", "description": "..." }
     *   },
     *   "required": ["path"]
     * }
     * </pre>
     */
    JsonObject toToolSchema();

    /**
     * Executes the tool call.
     *
     * @param arguments parsed parameter map (keys are parameter names)
     * @return string result that will be returned to the LLM as a tool message
     */
    String execute(Map<String, Object> arguments);

    /** Thrown to signal a tool-level error. */
    final class ToolException extends RuntimeException {
        public ToolException(String message) { super(message); }
        public ToolException(String message, Throwable cause) { super(message, cause); }
    }
}