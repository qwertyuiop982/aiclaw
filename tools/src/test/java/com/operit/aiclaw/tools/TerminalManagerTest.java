package com.operit.aiclaw.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TerminalManagerTest {
    @Test
    void keepsWorkingDirectoryAndEnvironmentAcrossCommands() throws Exception {
        Path tempDir = Files.createTempDirectory("aiclaw-terminal-cwd-");
        try (TerminalManager manager = new TerminalManager()) {
            String id = manager.create(System.getProperty("user.dir"));
            String first = manager.execute(id, "export AICLAW_TEST_VALUE=kept; cd '" + tempDir + "'; printf '%s' \"$AICLAW_TEST_VALUE\"", 5);
            assertTrue(first.contains("kept"), first);
            String expectedCwd = tempDir.toAbsolutePath().normalize().toString();
            String second = manager.execute(id, "printf '%s:%s' \"$PWD\" \"$AICLAW_TEST_VALUE\"", 5);
            assertTrue(second.contains(expectedCwd), second);
            assertTrue(second.contains("kept"), second);
        }
    }

    @Test
    void supportsInputForInteractiveCommand() {
        try (TerminalManager manager = new TerminalManager()) {
            String id = manager.create(System.getProperty("user.dir"));
            String result = manager.execute(id, "read value; printf 'value=%s' \"$value\"", 1);
            assertTrue(result.contains("running=true"), result);
            manager.input(id, "hello", true);
            String waited = manager.waitFor(id, 5);
            assertTrue(waited.contains("hello"), waited);
        }
    }

    @Test
    void closeRemovesSession() {
        TerminalManager manager = new TerminalManager();
        String id = manager.create(System.getProperty("user.dir"));
        assertTrue(manager.list().contains(id));
        assertTrue(manager.close(id).contains("closed"));
        assertFalse(manager.list().contains(id));
        manager.close();
    }
}