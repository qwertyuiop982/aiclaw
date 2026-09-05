package com.operit.aiclaw.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages persistent local terminal sessions. This is a session manager, not a security sandbox.
 * Each session owns one bash process and keeps its cwd/environment across commands.
 */
public final class TerminalManager implements AutoCloseable {
    private static final int BUFFER_LIMIT = 64 * 1024;
    private static final String PROMPT_MARKER = "__AICLAW_PROMPT_DONE__";
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public String create(String cwd) {
        String id = "term_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sessions.put(id, new Session(id, cwd));
        return id;
    }

    public String execute(String id, String command, int timeoutSeconds) {
        Session session = require(id);
        return session.execute(command, timeoutSeconds);
    }

    public String input(String id, String input, boolean enter) {
        Session session = require(id);
        return session.input(input, enter);
    }

    public String screen(String id, int maxChars) {
        Session session = require(id);
        return session.snapshot(maxChars);
    }

    public String transcriptPath(String id) {
        return require(id).transcript.toString();
    }

    public String waitFor(String id, int timeoutSeconds) {
        Session session = require(id);
        return session.waitFor(timeoutSeconds);
    }

    public String close(String id) {
        Session session = sessions.remove(id);
        if (session == null) return "terminal not found: " + id;
        session.close();
        return "closed terminal: " + id;
    }

    public List<String> list() {
        return new ArrayList<>(sessions.keySet());
    }

    private Session require(String id) {
        if (id == null || id.isBlank()) throw new ToolException("terminal_id is required");
        Session session = sessions.get(id);
        if (session == null) throw new ToolException("terminal not found: " + id);
        return session;
    }

    @Override
    public void close() {
        for (String id : new ArrayList<>(sessions.keySet())) close(id);
    }

    private static final class Session implements AutoCloseable {
        private final String id;
        private final Process process;
        private final BufferedWriter stdin;
        private final StringBuilder output = new StringBuilder();
        private final Path transcript;
        private volatile boolean closed;
        private volatile boolean runningCommand;
        private volatile String activeMarker;
        private volatile int activeBefore;

        Session(String id, String cwd) {
            this.id = id;
            this.transcript = Path.of(System.getProperty("java.io.tmpdir"), "aiclaw-terminal-" + id + ".log");
            try {
                Files.deleteIfExists(transcript);
                ProcessBuilder builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-i");
                if (cwd != null && !cwd.isBlank()) builder.directory(Path.of(cwd).toAbsolutePath().normalize().toFile());
                builder.redirectErrorStream(true);
                builder.environment().put("PS1", "");
                builder.environment().put("PS2", "");
                builder.environment().put("HISTFILE", "/dev/null");
                Process created = builder.start();
                BufferedWriter createdStdin = new BufferedWriter(
                        new OutputStreamWriter(created.getOutputStream(), StandardCharsets.UTF_8));
                process = created;
                stdin = createdStdin;
                // Keep the shell startup output out of command results; no initialization command is needed.
                Thread reader = new Thread(this::readOutput, "aiclaw-terminal-reader-" + id);
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                throw new ToolException("cannot create terminal: " + e.getMessage(), e);
            }
        }

        String execute(String command, int timeoutSeconds) {
            ensureOpen();
            if (runningCommand) throw new ToolException("terminal is busy; use wait or input first");
            String marker = "__AICLAW_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__";
            int before;
            synchronized (output) { before = output.length(); }
            activeBefore = before;
            activeMarker = marker;
            runningCommand = true;
            try {
                stdin.write(command + "\n");
                if (!looksInteractive(command)) {
                    stdin.write("echo " + marker + ":$?\n");
                }
                stdin.flush();
            } catch (IOException e) {
                runningCommand = false;
                throw new ToolException("terminal write failed: " + e.getMessage(), e);
            }
            String result = waitForMarker(marker, timeoutSeconds, before);
            return result;
        }

        synchronized String input(String text, boolean enter) {
            ensureOpen();
            try {
                stdin.write(text == null ? "" : text);
                if (enter) {
                    stdin.newLine();
                    if (activeMarker != null) stdin.write("echo " + activeMarker + ":$?\n");
                }
                stdin.flush();
                return "input sent to " + id;
            } catch (IOException e) {
                throw new ToolException("terminal input failed: " + e.getMessage(), e);
            }
        }

        String waitFor(int timeoutSeconds) {
            ensureOpen();
            if (runningCommand && activeMarker != null) {
                return waitForMarker(activeMarker, timeoutSeconds, activeBefore);
            }
            return snapshot(12000) + "\n[running=false]";
        }

        String snapshot(int maxChars) {
            synchronized (output) {
                int limit = Math.max(100, maxChars);
                int start = Math.max(0, output.length() - limit);
                return output.substring(start) + (start > 0 ? "\n...(older output truncated)" : "");
            }
        }

        private String waitForMarker(String marker, int timeoutSeconds, int before) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (System.nanoTime() < deadline) {
                synchronized (output) {
                    int index = output.indexOf(marker);
                    if (index >= 0) {
                        int end = output.indexOf("\n", index);
                        if (end < 0) end = output.length();
                        int start = Math.max(0, before);
                        if (start > index) start = 0;
                        String all = output.substring(start, index);
                        runningCommand = false;
                        return "[terminal=" + id + "][running=false]\n" + trim(all, 12000);
                    }
                }
                if (!process.isAlive()) {
                    runningCommand = false;
                    throw new ToolException("terminal process exited with code " + process.exitValue());
                }
                sleep(20);
            }
            return "[terminal=" + id + "][running=true][timeout]\n" + snapshot(12000);
        }

        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[2048];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    synchronized (output) {
                        output.append(buffer, 0, count);
                        try {
                            Files.writeString(transcript, new String(buffer, 0, count), StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                        } catch (IOException ignored) { }
                        if (output.length() > BUFFER_LIMIT) output.delete(0, output.length() - BUFFER_LIMIT);
                    }
                }
            } catch (IOException ignored) {
                // Process shutdown is expected during close.
            }
        }

        private void ensureOpen() {
            if (closed || !process.isAlive()) throw new ToolException("terminal is closed: " + id);
        }

        @Override public void close() {
            closed = true;
            process.destroy();
            try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }

        private static boolean looksInteractive(String command) {
            String normalized = command == null ? "" : command.toLowerCase(java.util.Locale.ROOT);
            return normalized.matches(".*(^|[;&| ])(read|select|passwd|ssh|python|jshell)([ ;&|].*)?");
        }

        private static String trim(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit) + "\n...(truncated)";
        }
        private static void sleep(long millis) {
            try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
