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

/** Issues a simple HTTP GET and returns the status code and a truncated body. */
public class HttpGetTool implements Tool {

    private static final int BODY_LIMIT = 8192;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "http_get"; }

    @Override
    public String description() {
        return "Issues an HTTP GET and returns the status code plus the response body (truncated to 8 KB).";
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
        JsonObject headers = new JsonObject();
        headers.addProperty("type", "string");
        headers.addProperty("description", "Optional extra request headers, as a JSON object string");
        props.add("headers_json", headers);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"url\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String url = ToolArgs.requiredString(arguments, "url");
        URI uri = UrlPolicy.validate(url);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            Object headersJson = arguments.get("headers_json");
            if (headersJson instanceof String s && !s.isBlank()) {
                JsonObject obj = JsonParser.parseString(s).getAsJsonObject();
                obj.entrySet().forEach(e -> b.header(e.getKey(), e.getValue().getAsString()));
            }
            HttpResponse<String> resp = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body() == null ? "" : resp.body();
            if (body.length() > BODY_LIMIT) {
                body = body.substring(0, BODY_LIMIT) + "\n... (truncated)";
            }
            return "[status=" + resp.statusCode() + "]\n" + body;
        } catch (Exception e) {
            throw new ToolException("http_get failed: " + e.getMessage(), e);
        }
    }
}