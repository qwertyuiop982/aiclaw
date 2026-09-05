package com.operit.aiclaw.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTML to plain text + image URL list (approach A: only inline image URLs, no OCR).
 *
 * <p>Design principles:</p>
 * <ul>
 *   <li>Prefer the actual body: strip script/style/nav/footer/header/aside noise nodes.</li>
 *   <li>Extract every {@code <img src>}, resolving relative URLs against the base URL.</li>
 *   <li>Attempt OCR on inline images; if it is unavailable, fall back to {@code [image: URL]}
 *       placeholders in the plain text.</li>
 * </ul>
 */
public final class HtmlTextExtractor {

    private static final int MIN_IMAGE_SIDE = 32;

    private HtmlTextExtractor() {}

    /** The extraction result. */
    public static class Result {
        public final String title;
        public final String text;        // plain text
        public final List<String> images; // absolute URLs
        public final Map<String, String> imageOcr; // url -> OCR text (empty when OCR is unavailable)

        public Result(String title, String text, List<String> images, Map<String, String> imageOcr) {
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.images = images == null ? List.of() : images;
            this.imageOcr = imageOcr == null ? Map.of() : imageOcr;
        }
    }

    /**
     * Extracts the plain text and the image URL list from {@code html}, resolving relative URLs
     * against {@code baseUrl}. Each image is passed through OCR when available.
     */
    public static Result extract(String html, String baseUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, baseUrl == null ? "" : baseUrl);

        // 1. Strip noise nodes that bury the body text.
        doc.select("script, style, noscript, iframe, svg, " +
                "nav, header, footer, aside, form, " +
                "[role=navigation], [aria-hidden=true]").remove();

        // 2. Title.
        String title = doc.title();

        // 3. Body text: prefer article / main, then the document body.
        Element body = doc.selectFirst("article, main, [role=main]");
        if (body == null) body = doc.body();
        String text = body == null ? "" : body.text();

        // 4. Image URLs.
        List<String> images = new ArrayList<>();
        Elements imgs = doc.select("img[src]");
        for (Element img : imgs) {
            String src = img.absUrl("src");
            if (src.isBlank() || src.startsWith("data:")) continue;
            // Filter out trivially small icons by their declared width/height attributes.
            int width = parseSize(img.attr("width"));
            int height = parseSize(img.attr("height"));
            if ((width > 0 && width < MIN_IMAGE_SIDE) || (height > 0 && height < MIN_IMAGE_SIDE)) continue;
            if (!images.contains(src)) images.add(src);
        }

        // 5. OCR pass when available (approach A).
        Map<String, String> ocr = new LinkedHashMap<>();
        if (OcrBridge.isAvailable()) {
            for (String url : images) {
                try {
                    String text2 = OcrBridge.recognize(url);
                    if (text2 != null && !text2.isBlank()) ocr.put(url, text2);
                } catch (Exception ignored) {
                    // OCR is best-effort; skip failures.
                }
            }
        }

        return new Result(title, text, images, ocr);
    }

    private static int parseSize(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    /** Resolves {@code maybeRelative} against {@code base}. Absolute URLs are returned as-is. */
    public static String absolutize(String base, String maybeRelative) {
        if (maybeRelative == null || maybeRelative.isBlank()) return "";
        try {
            URI b = base == null ? null : new URI(base);
            URI r = new URI(maybeRelative);
            if (r.isAbsolute()) return r.toString();
            return b == null ? r.toString() : b.resolve(r).toString();
        } catch (URISyntaxException e) {
            return maybeRelative;
        }
    }
}