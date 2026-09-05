package com.operit.aiclaw.tools;

/** Expected failure raised by a local tool. */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}