package com.operit.aiclaw.llm;

/**
 * Thrown for failures in the LLM call path (network errors, non-2xx responses, or
 * malformed payloads that the client cannot recover from).
 */
public class LlmException extends RuntimeException {
    private final int statusCode;

    public LlmException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP status code if the failure came from an HTTP response, otherwise {@code -1}. */
    public int getStatusCode() {
        return statusCode;
    }
}