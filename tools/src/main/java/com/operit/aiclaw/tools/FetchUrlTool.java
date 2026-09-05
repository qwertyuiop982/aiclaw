package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Fetches a URL and extracts its plain text and image URL list (OCR is a stub).
 *
 * <p>Important boundaries (also surfaced to the LLM via {@link #description()}):</p>
 * <ul>
 *   <li>Fetches each URL exactly once - no recursion.</li>
 *   <li>Parses HTML; JSON / plain text responses are returned verbatim (truncated).</li>
 *   <li>Image OCR is not configured by default ({@link OcrBridge#isAvailable()} is false);
 *       only image URLs are listed.</li>
 *   <li>The result is truncated (default 12 KB of text). Use {@code max_chars} to adjust.</li>
 * </ul>
 */
public class FetchUrlTool implements Tool {

    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int MAX_IMAGES = 30;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "fetch_url"; }

    @Override
    public String description() {
        return "Fetches a URL over HTTP GET and extracts the plain text and image URL list from the HTML. "
                + "Output format: [title], [text], [images] (one URL per line). "
                + "If OCR is configured, each image is followed by its OCR text; otherwise images are listed as URLs only.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject url = new JsonObject();
        url.addProperty("type", "string");
        url.addProperty("description", "Target URL (must include the http/https scheme)");
        props.add("url", url);
        JsonObject max = new JsonObject();
        max.addProperty("type", "integer");
        max.addProperty("description", "Maximum number of characters in the returned text (default 12000)");
        props.add("max_chars", max);
        JsonObject include = new JsonObject();
        include.addProperty("type", "boolean");
        include.addProperty("description", "Include the image URL list in the result (default true)");
        props.add("include_images", include);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"url\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String url = ToolArgs.requiredString(arguments, "url");
        URI uri = UrlPolicy.validate(url);
        int max = ToolArgs.boundedInt(arguments, "max_chars", 12_000, 100, 1_000_000);
        boolean includeImages = ToolArgs.bool(arguments, "include_images", true);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ToolException("fetch_url failed: " + e.getMessage(), e);
        }

        String body = resp.body() == null ? "" : resp.body();
        String contentType = resp.headers().firstValue("content-type").orElse("").toLowerCase();

        // Non-HTML: pass the body through verbatim (truncated).
        if (!contentType.contains("html")) {
            String trimmed = body.length() > max ? body.substring(0, max) + "\n...(truncated)" : body;
            return "[status=" + resp.statusCode() + " content-type=" + contentType + "]\n" + trimmed;
        }

        HtmlTextExtractor.Result r = HtmlTextExtractor.extract(body, url);

        StringBuilder out = new StringBuilder();
        out.append("[status=").append(resp.statusCode()).append("]\n");
        if (!r.title.isBlank()) out.append("Title: ").append(r.title).append("\n");
        out.append("--- TEXT ---\n");
        String text = r.text;
        if (text.length() > max) text = text.substring(0, max) + "\n...(truncated)";
        out.append(text);

        if (includeImages && !r.images.isEmpty()) {
            out.append("\n\n--- IMAGES (").append(r.images.size()).append(") ---\n");
            int count = 0;
            for (String img : r.images) {
                out.append("- ").append(img);
                if (r.imageOcr.containsKey(img)) {
                    String ocr = r.imageOcr.get(img);
                    if (ocr.length() > 200) ocr = ocr.substring(0, 200) + "...";
                    out.append("\n    [OCR] ").append(ocr);
                }
                out.append("\n");
                if (++count >= MAX_IMAGES) {
                    out.append("... (more omitted)\n");
                    break;
                }
            }
            if (!OcrBridge.isAvailable() && !r.images.isEmpty()) {
                out.append("\n(OCR is not configured; only image URLs are listed.)\n");
            }
        }

        return out.toString();
    }
}