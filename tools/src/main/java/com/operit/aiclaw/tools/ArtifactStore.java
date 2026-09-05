package com.operit.aiclaw.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** File-backed store for large tool output and terminal transcripts. */
public final class ArtifactStore {
    private final Path root;

    public ArtifactStore(Path root) throws IOException {
        if (root == null) throw new IllegalArgumentException("artifact root must not be null");
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }
    public Path root() { return root; }
    public ArtifactRef put(String producer, String content) throws IOException {
        String id = "art_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path file = root.resolve(id + ".txt");
        String value = content == null ? "" : content;
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return new ArtifactRef(id, producer, file, value.length(), sha256(value), Instant.now().toString());
    }
    public String read(String id, int maxChars) throws IOException {
        if (id == null || !id.matches("art_[A-Za-z0-9]+")) throw new IOException("invalid artifact id");
        Path file = root.resolve(id + ".txt").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("artifact not found: " + id);
        String value = Files.readString(file, StandardCharsets.UTF_8);
        int limit = Math.max(100, maxChars);
        return value.length() <= limit ? value : value.substring(0, limit) + "\n...(truncated)";
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { return "unavailable"; }
    }
    public record ArtifactRef(String id, String producer, Path path, long size, String sha256, String createdAt) {}
}