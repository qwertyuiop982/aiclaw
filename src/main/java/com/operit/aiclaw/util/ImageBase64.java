package com.operit.aiclaw.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;

/**
 * Encodes an image file into an OpenAI multimodal {@code image_url} data URI.
 *
 * <p>Usage:</p>
 * <pre>
 *   String dataUri = ImageBase64.toDataUri("/absolute/path/image.png");
 *   // -&gt; "data:image/png;base64,iVBORw0K..."
 * </pre>
 *
 * <p>The 5&nbsp;MB per-image limit keeps the encoded payload from ballooning the request body.
 * Endpoints that accept larger inputs typically offer an {@code image_url} with an {@code url}
 * field that points at a hosted copy; this utility is for local files only.</p>
 */
public final class ImageBase64 {

    /** MIME type per file extension. Unknown extensions fall back to {@code application/octet-stream}. */
    private static String mimeFor(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /** Reads the file and base64-encodes it into a {@code data:} URI. */
    public static String toDataUri(String path) throws IOException {
        Path file = Paths.get(path);
        if (!Files.exists(file)) throw new IOException("image not found: " + path);
        byte[] bytes = Files.readAllBytes(file);
        // 5 MB cap per image prevents the base64 payload from inflating the request body.
        if (bytes.length > 5 * 1024 * 1024) {
            throw new IOException("image too large (>5MB): " + path);
        }
        String mime = mimeFor(file.getFileName().toString());
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** Whether the given path looks like an image based on its extension. */
    public static boolean looksLikeImage(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }
}