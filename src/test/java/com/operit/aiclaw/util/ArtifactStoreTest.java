package com.operit.aiclaw.util;

import com.operit.aiclaw.tools.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactStoreTest {
    @Test
    void storesAndReadsLargeOutputSafely() throws Exception {
        ArtifactStore store = new ArtifactStore(Files.createTempDirectory("aiclaw-artifacts-"));
        String value = "x".repeat(1000);
        ArtifactStore.ArtifactRef ref = store.put("test", value);
        assertTrue(ref.id().startsWith("art_"));
        assertEquals(value, store.read(ref.id(), 2000));
        assertTrue(store.read(ref.id(), 100).contains("truncated"));
    }
}