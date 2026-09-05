package com.operit.aiclaw.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplInputTest {

    @Test
    void pipeInputPreservesUtf8AndLineBoundaries() throws Exception {
        byte[] bytes = "你好，aiclaw\n第二行🙂\n".getBytes(StandardCharsets.UTF_8);

        try (ReplInput input = new ReplInput(new ByteArrayInputStream(bytes))) {
            assertEquals("你好，aiclaw", input.readLine());
            assertEquals("第二行🙂", input.readLine());
            assertNull(input.readLine());
        }
    }

    @Test
    void pipeInputLeavesVisibleEscForReplCompatibility() throws Exception {
        byte[] bytes = "\u001b\nesc\n".getBytes(StandardCharsets.UTF_8);

        try (ReplInput input = new ReplInput(new ByteArrayInputStream(bytes))) {
            assertEquals("\u001b", input.readLine());
            assertEquals("esc", input.readLine());
            assertNull(input.readLine());
        }
    }

    @Test
    void pipeInputPreservesShortcutControlCharactersForCliDispatch() throws Exception {
        byte[] bytes = (ReplInput.CTRL_A + "\n" + ReplInput.CTRL_Y + "\n" + ReplInput.CTRL_F + "\n")
                .getBytes(StandardCharsets.UTF_8);

        try (ReplInput input = new ReplInput(new ByteArrayInputStream(bytes))) {
            assertEquals(ReplInput.CTRL_A, input.readLine());
            assertEquals(ReplInput.CTRL_Y, input.readLine());
            assertEquals(ReplInput.CTRL_F, input.readLine());
            assertNull(input.readLine());
        }
    }

    @Test
    void closeDoesNotCloseCallerOwnedPipe() throws Exception {
        TrackingInputStream bytes = new TrackingInputStream("中文\n".getBytes(StandardCharsets.UTF_8));
        ReplInput input = new ReplInput(bytes);
        input.close();

        assertEquals(false, bytes.closed);
    }

    private static final class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            this.delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
