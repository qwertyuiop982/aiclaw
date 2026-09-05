package com.operit.aiclaw.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bing search - fetches only a list of result links; does not fetch page content.
 *
 * <p>How it works:</p>
 * <ol>
 *   <li>Request {@code https://www.bing.com/search?q={query}&setlang=...}.</li>
 *   <li>Parse the HTML and extract result items ({@code <li class="b_algo">} / {@code .b_algo} /
 *       {@code .b_title}).</li>
 *   <li>Emit only {@code {title, url}} pairs from each result.</li>
 *   <li>Let the agent decide whether to call {@code fetch_url} for the details.</li>
 * </ol>
 *
 * <p>Note: Bing does not expose a public search API, so this tool scrapes HTML directly. It
 * requires a real User-Agent, a sensible Accept-Language header, and may need cookies for
 * non-trivial queries.</p>
 */
public class BingSearchTool implements Tool {

    private static final String UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String name() { return "bing_search"; }

    @Override
    public String description() {
        return "Searches Bing for a query and returns a list of result links (without fetching their content). "
                + "Each entry contains the title and URL; call fetch_url to retrieve the body of any link you care about.";
    }

    @Override
    public JsonObject toToolSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Search query");
        props.add("query", query);
        JsonObject max = new JsonObject();
        max.addProperty("type", "integer");
        max.addProperty("description", "Maximum number of results to return (default 10, maximum 30)");
        props.add("max_results", max);
        JsonObject lang = new JsonObject();
        lang.addProperty("type", "string");
        lang.addProperty("description", "UI language for the search page, e.g. en-US or zh-CN (default en-US)");
        props.add("lang", lang);
        schema.add("properties", props);
        schema.add("required", JsonParser.parseString("[\"query\"]"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = ToolArgs.requiredString(arguments, "query");
        int max = ToolArgs.boundedInt(arguments, "max_results", 10, 1, 30);
        String lang = ToolArgs.string(arguments, "lang", "en-US");
        if (lang.isBlank()) lang = "en-US";

        String searchUrl = "https://www.bing.com/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8) +
                "&setlang=" + URLEncoder.encode(lang, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", lang + "," + (lang.startsWith("zh") ? "en-US;q=0.8" : "zh-CN;q=0.6"))
                .header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive")
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ToolException("bing_search HTTP failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() / 100 != 2) {
            return "[bing_search failed] HTTP " + resp.statusCode() +
                    "\n" + truncate(resp.body(), 500);
        }

        return parse(resp.body(), max);
    }

    private String parse(String html, int max) {
        Document doc = Jsoup.parse(html);
        // Strip noise that would confuse the link extractor.
        doc.select("script, style, noscript").remove();

        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();

        // 1) Primary results: <li class="b_algo">.
        for (Element li : doc.select("li.b_algo")) {
            Element anchor = li.selectFirst("h2 > a, a.tilk, a");
            if (anchor == null) continue;
            String href = anchor.absUrl("href");
            if (href.isBlank()) continue;
            String title = anchor.text();
            if (!isUsefulHref(href)) continue;
            if (seen.add(href)) {
                out.append("- ").append(title).append("\n  ").append(href).append("\n");
                if (seen.size() >= max) break;
            }
        }

        // 2) Fallback: scan any <a href>, filtering ads and navigation links.
        if (seen.size() < max) {
            for (Element anchor : doc.select("a[href]")) {
                String href = anchor.absUrl("href");
                if (!isUsefulHref(href)) continue;
                String title = anchor.text();
                if (title.isBlank() || title.length() > 200) continue;
                if (seen.add(href)) {
                    out.append("- ").append(title).append("\n  ").append(href).append("\n");
                    if (seen.size() >= max) break;
                }
            }
        }

        if (out.length() == 0) {
            return "[bing_search] no results (Bing may have rate-limited or returned a CAPTCHA). "
                    + "Try a different query, set a real User-Agent, or use http_get to inspect the page.";
        }
        return out.toString();
    }

    private static boolean isUsefulHref(String href) {
        if (href == null) return false;
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("#") || lower.startsWith("mailto:")) return false;
        if (lower.contains("bing.com/")) return false;            // Site navigation.
        if (lower.contains("microsoft.com/")) return false;      // Microsoft-owned.
        if (lower.contains("msn.com/")) return false;
        if (!lower.startsWith("http")) return false;
        return true;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}