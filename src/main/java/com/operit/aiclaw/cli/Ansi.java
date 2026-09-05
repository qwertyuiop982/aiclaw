package com.operit.aiclaw.cli;

/**
 * Minimal ANSI color helper for CLI output.
 *
 * Colors are only applied when stdout looks like an interactive terminal that supports them;
 * this mirrors the TERM check already used by ModelPicker/ThinkingPicker. Respects the NO_COLOR
 * convention (https://no-color.org/) and a plain "dumb" TERM.
 */
public final class Ansi {
    private static final boolean ENABLED = detect();

    private Ansi() {}

    public static final String RESET = "\u001b[0m";
    public static final String BOLD = "\u001b[1m";
    public static final String DIM = "\u001b[2m";

    public static final String RED = "\u001b[31m";
    public static final String GREEN = "\u001b[32m";
    public static final String YELLOW = "\u001b[33m";
    public static final String BLUE = "\u001b[34m";
    public static final String MAGENTA = "\u001b[35m";
    public static final String CYAN = "\u001b[36m";
    public static final String GRAY = "\u001b[90m";

    public static boolean enabled() { return ENABLED; }

    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) return false;
        String term = System.getenv("TERM");
        if (term == null || term.isBlank() || "dumb".equalsIgnoreCase(term)) return false;
        return System.console() != null;
    }

    private static String wrap(String code, String text) {
        if (!ENABLED || text == null) return text == null ? "" : text;
        return code + text + RESET;
    }

    public static String red(String text) { return wrap(RED, text); }
    public static String green(String text) { return wrap(GREEN, text); }
    public static String yellow(String text) { return wrap(YELLOW, text); }
    public static String blue(String text) { return wrap(BLUE, text); }
    public static String magenta(String text) { return wrap(MAGENTA, text); }
    public static String cyan(String text) { return wrap(CYAN, text); }
    public static String gray(String text) { return wrap(GRAY, text); }
    public static String bold(String text) { return wrap(BOLD, text); }

    /** Success line, e.g. confirmations. */
    public static String success(String text) { return green(text); }
    /** Warning line. */
    public static String warn(String text) { return yellow(text); }
    /** Error line. */
    public static String error(String text) { return red(text); }
    /** Informational / status line. */
    public static String info(String text) { return cyan(text); }
    /** Muted/secondary text (paths, hints). */
    public static String muted(String text) { return gray(text); }
}
