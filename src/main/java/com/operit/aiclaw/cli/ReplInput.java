package com.operit.aiclaw.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * REPL input layer.
 *
 * {@link BufferedReader#readLine()} returns only after Enter, so it cannot observe a bare Escape
 * key. In a TTY this class temporarily enters non-canonical mode, decodes UTF-8 through a
 * {@link Reader}, and emits both text input and REPL control keys. Pipe/redirection input keeps
 * BufferedReader behavior for script compatibility.
 *
 * <p>A terminal arrow key is an escape sequence such as ESC [ A/B. A short look-ahead window is
 * therefore required before treating ESC as a bare mode-switch key. Normal exit, EOF, I/O errors,
 * and JVM shutdown all attempt to restore terminal state. SIGKILL cannot run JVM cleanup code.</p>
 */
public final class ReplInput implements AutoCloseable {
    public static final String ESCAPE = "\u001b";
    public static final String CTRL_A = "\u0001";
    public static final String CTRL_Y = "\u0019";
    public static final String CTRL_F = "\u0006";

    private static final int ESC = 0x1b;
    private static final int ESCAPE_SEQUENCE_WAIT_MILLIS = 35;

    /** Keys used by the REPL and the interactive model picker. */
    public enum Key {
        EOF,
        ENTER,
        ESC,
        CTRL_A,
        CTRL_Y,
        CTRL_F,
        UP,
        DOWN,
        BACKSPACE,
        DELETE,
        LEFT,
        RIGHT,
        CTRL_D,
        CHARACTER,
        OTHER
    }

    private record KeyStroke(Key key, int codePoint) {}

    private final InputStream in;
    private final boolean tty;
    private final BufferedReader lineReader;
    private final Reader ttyReader;
    private final Deque<Integer> pendingUnits = new ArrayDeque<>();

    private String savedStty;
    private boolean terminalChanged;
    private Thread shutdownHook;
    private volatile boolean closed;

    public ReplInput() throws IOException {
        this(System.in);
    }

    public ReplInput(InputStream in) throws IOException {
        if (in == null) throw new NullPointerException("in");
        this.in = in;

        // A caller-supplied InputStream need not be this process's fd 0, so do not put it in raw mode.
        this.tty = in == System.in && isTty();
        if (tty) {
            this.lineReader = null;
            this.ttyReader = new InputStreamReader(in, StandardCharsets.UTF_8);
            try {
                enableRawMode();
                installShutdownHook();
            } catch (IOException | RuntimeException e) {
                // stty could have changed state before returning an error.
                restoreTerminal();
                throw e;
            }
        } else {
            this.lineReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            this.ttyReader = null;
        }
    }

    public boolean isInteractiveTerminal() {
        return tty;
    }

    /** Returns a line; bare Esc, Ctrl+A, Ctrl+Y, and Ctrl+F return their corresponding constants. */
    public String readLine() throws IOException {
        if (closed) throw new IOException("REPL input is closed");
        if (!tty) return lineReader.readLine();

        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (true) {
            KeyStroke stroke = readKeyStroke();
            switch (stroke.key()) {
                case EOF -> {
                    return out.length() == 0 ? null : out.toString();
                }
                case ENTER -> {
                    echo("\n");
                    return out.toString();
                }
                case ESC -> {
                    if (out.length() == 0) return ESCAPE;
                }
                case CTRL_A -> {
                    if (out.length() == 0) return CTRL_A;
                    cursor = 0;
                    redrawLine(out, cursor);
                }
                case CTRL_Y -> {
                    if (out.length() == 0) return CTRL_Y;
                }
                case CTRL_F -> {
                    if (out.length() == 0) return CTRL_F;
                    if (cursor < out.length()) cursor = nextCodePoint(out, cursor);
                    redrawLine(out, cursor);
                }
                case LEFT -> {
                    if (cursor > 0) cursor = previousCodePoint(out, cursor);
                    redrawLine(out, cursor);
                }
                case RIGHT -> {
                    if (cursor < out.length()) cursor = nextCodePoint(out, cursor);
                    redrawLine(out, cursor);
                }
                case UP, DOWN, OTHER -> {
                    // The normal prompt has no history navigation. The picker consumes arrows itself.
                }
                case BACKSPACE -> {
                    if (cursor > 0) {
                        int start = previousCodePoint(out, cursor);
                        out.delete(start, cursor);
                        cursor = start;
                        redrawLine(out, cursor);
                    }
                }
                case DELETE -> {
                    if (cursor < out.length()) {
                        out.delete(cursor, nextCodePoint(out, cursor));
                        redrawLine(out, cursor);
                    }
                }
                case CTRL_D -> {
                    return out.length() == 0 ? null : out.toString();
                }
                case CHARACTER -> {
                    String text = new String(Character.toChars(stroke.codePoint()));
                    out.insert(cursor, text);
                    cursor += text.length();
                    redrawLine(out, cursor);
                }
            }
        }
    }

    /** Reads one raw key for a transient interactive view such as the model picker. */
    public Key readKey() throws IOException {
        if (closed) throw new IOException("REPL input is closed");
        if (!tty) throw new IOException("raw key input requires an interactive terminal");
        return readKeyStroke().key();
    }

    private KeyStroke readKeyStroke() throws IOException {
        int codePoint = readCodePoint();
        if (codePoint < 0) return new KeyStroke(Key.EOF, -1);
        if (codePoint == ESC) return readEscapeKey();
        if (codePoint == '\n' || codePoint == '\r') return new KeyStroke(Key.ENTER, codePoint);
        if (codePoint == 0x08 || codePoint == 0x7f) return new KeyStroke(Key.BACKSPACE, codePoint);
        if (codePoint == 0x04) return new KeyStroke(Key.CTRL_D, codePoint);
        if (codePoint == 0x01) return new KeyStroke(Key.CTRL_A, codePoint);
        if (codePoint == 0x19) return new KeyStroke(Key.CTRL_Y, codePoint);
        if (codePoint == 0x06) return new KeyStroke(Key.CTRL_F, codePoint);
        if (Character.isISOControl(codePoint)) return new KeyStroke(Key.OTHER, codePoint);
        return new KeyStroke(Key.CHARACTER, codePoint);
    }

    private KeyStroke readEscapeKey() throws IOException {
        int prefix = readUnitWithin(ESCAPE_SEQUENCE_WAIT_MILLIS);
        if (prefix != '[') {
            if (prefix >= 0) unreadUnit(prefix);
            return new KeyStroke(Key.ESC, ESC);
        }

        int finalByte = readUnitWithin(ESCAPE_SEQUENCE_WAIT_MILLIS);
        if (finalByte == 'A') return new KeyStroke(Key.UP, ESC);
        if (finalByte == 'B') return new KeyStroke(Key.DOWN, ESC);
        if (finalByte == 'C') return new KeyStroke(Key.RIGHT, ESC);
        if (finalByte == 'D') return new KeyStroke(Key.LEFT, ESC);
        if (finalByte < 0) {
            unreadUnit(prefix);
            return new KeyStroke(Key.ESC, ESC);
        }

        // Decode common CSI editing keys, including Delete (ESC [ 3 ~).
        StringBuilder parameter = new StringBuilder();
        int current = finalByte;
        while (current >= 0 && current != '~'
                && !(current >= 'A' && current <= 'Z')
                && !(current >= 'a' && current <= 'z')) {
            parameter.append((char) current);
            current = readUnitWithin(ESCAPE_SEQUENCE_WAIT_MILLIS);
        }
        if (current == '~' && "3".equals(parameter.toString())) {
            return new KeyStroke(Key.DELETE, ESC);
        }
        if (current == '~' && ("1".equals(parameter.toString()) || "7".equals(parameter.toString()))) {
            return new KeyStroke(Key.OTHER, ESC); // Home; handled as non-command input for now.
        }
        if (current == '~' && ("4".equals(parameter.toString()) || "8".equals(parameter.toString()))) {
            return new KeyStroke(Key.OTHER, ESC); // End; handled as non-command input for now.
        }
        return new KeyStroke(Key.OTHER, ESC);
    }

    private void consumeEscapeTail(int current) throws IOException {
        int unit = current;
        for (int i = 0; i < 8 && unit >= 0; i++) {
            if ((unit >= 'A' && unit <= 'Z') || (unit >= 'a' && unit <= 'z') || unit == '~') return;
            unit = readUnitWithin(ESCAPE_SEQUENCE_WAIT_MILLIS);
        }
    }

    /** Reader decodes UTF-8 to UTF-16; combine a surrogate pair so editing is code-point based. */
    private int readCodePoint() throws IOException {
        int first = readUnit();
        if (first < 0) return -1;

        char firstChar = (char) first;
        if (Character.isHighSurrogate(firstChar)) {
            int second = readUnit();
            if (second >= 0 && Character.isLowSurrogate((char) second)) {
                return Character.toCodePoint(firstChar, (char) second);
            }
            if (second >= 0) unreadUnit(second);
        }
        return first;
    }

    private int readUnit() throws IOException {
        if (!pendingUnits.isEmpty()) return pendingUnits.removeFirst();
        return ttyReader.read();
    }

    private int readUnitWithin(int waitMillis) throws IOException {
        if (!pendingUnits.isEmpty()) return pendingUnits.removeFirst();

        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (ttyReader.ready()) return ttyReader.read();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return ttyReader.ready() ? ttyReader.read() : -1;
    }

    private void unreadUnit(int unit) {
        pendingUnits.addFirst(unit);
    }

    private static int previousCodePoint(StringBuilder text, int cursor) {
        return text.offsetByCodePoints(cursor, -1);
    }

    private static int nextCodePoint(StringBuilder text, int cursor) {
        return text.offsetByCodePoints(cursor, 1);
    }

    /** Repaints the current logical line and places the terminal cursor at the UTF-16 offset. */
    private static void redrawLine(StringBuilder text, int cursor) {
        // Clear to end, print the complete buffer, then move back from the end.
        StringBuilder ansi = new StringBuilder("\r\u001b[K").append(text);
        int distance = text.length() - cursor;
        if (distance > 0) ansi.append("\u001b[").append(distance).append('D');
        echo(ansi.toString());
    }

    private static void eraseLastCodePoint(StringBuilder out) {
        if (out.length() == 0) return;
        int start = out.offsetByCodePoints(out.length(), -1);
        out.delete(start, out.length());
        echo("\b \b");
    }

    private static void appendAndEcho(StringBuilder out, int codePoint) {
        String text = new String(Character.toChars(codePoint));
        out.append(text);
        echo(text);
    }

    private static void echo(String text) {
        System.out.print(text);
        System.out.flush();
    }

    private boolean isTty() {
        try {
            // inheritIO makes test -t inspect the same terminal as the Java process.
            Process p = new ProcessBuilder("sh", "-c", "test -t 0").inheritIO().start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void enableRawMode() throws IOException {
        try {
            Process saved = new ProcessBuilder("sh", "-c", "stty -g < /dev/tty")
                    .redirectErrorStream(true).start();
            byte[] stateBytes = saved.getInputStream().readAllBytes();
            int savedExit = saved.waitFor();
            savedStty = new String(stateBytes, StandardCharsets.UTF_8).trim();
            if (savedExit != 0 || savedStty.isBlank()) {
                throw new IOException("cannot read terminal settings");
            }

            Process raw = new ProcessBuilder("sh", "-c", "stty -icanon min 1 -echo < /dev/tty")
                    .redirectErrorStream(true).start();
            terminalChanged = true;
            raw.getInputStream().readAllBytes();
            if (raw.waitFor() != 0) throw new IOException("cannot enable raw terminal input");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while configuring terminal", e);
        }
    }

    private void installShutdownHook() {
        shutdownHook = new Thread(this::restoreTerminal, "aiclaw-repl-terminal-restore");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (RuntimeException e) {
            shutdownHook = null;
            throw e;
        }
    }

    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM is already shutting down, or the security policy forbids removal.
        } finally {
            shutdownHook = null;
        }
    }

    /** Best-effort terminal restoration; close and the shutdown hook may call this concurrently. */
    private synchronized void restoreTerminal() {
        if (!tty || !terminalChanged || savedStty == null || savedStty.isBlank()) return;
        try {
            Process restore = new ProcessBuilder("sh", "-c",
                    "stty " + shellQuote(savedStty) + " < /dev/tty")
                    .redirectErrorStream(true).start();
            restore.getInputStream().readAllBytes();
            if (restore.waitFor() == 0) {
                terminalChanged = false;
                savedStty = null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Keep state so a later close can retry without hiding the original error.
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
        closed = true;
        restoreTerminal();
        removeShutdownHook();
    }
}
