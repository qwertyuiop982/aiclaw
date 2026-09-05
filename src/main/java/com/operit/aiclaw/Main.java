package com.operit.aiclaw;

import com.operit.aiclaw.cli.Cli;

/**
 * Entry point for the {@code aiclaw} command-line tool.
 *
 * <p>Usage:</p>
 * <pre>
 *   aiclaw                       start the default "coder" agent (interactive)
 *   aiclaw list                  list available agents
 *   aiclaw run &lt;agent&gt;          start an interactive session with the named agent
 *   aiclaw ask &lt;agent&gt; &lt;msg&gt;   ask once and exit
 *   aiclaw chat &lt;agent&gt;         alias for {@code run}
 *   aiclaw --help                show help
 * </pre>
 */
public class Main {
    public static void main(String[] args) {
        try {
            int exitCode = new Cli().run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Fatal: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            System.exit(1);
        }
    }
}