package com.operit.aiclaw.tools;

/**
 * OCR bridge - approach A placeholder.
 *
 * <p>The current implementation only returns {@code false}; OCR is not configured. Callers
 * (notably {@link HtmlTextExtractor}) skip OCR when {@link #isAvailable()} is {@code false} and
 * fall back to a {@code [image: URL]} placeholder in the text.</p>
 *
 * <p>To wire up a real OCR backend:</p>
 * <ul>
 *   <li>Local: Tesseract via {@code net.sourceforge.tess4j:tess4j}.</li>
 *   <li>Remote: a model service that accepts image inputs.</li>
 * </ul>
 *
 * <p>Implementing {@link #recognize(String)} is enough; no other code needs to change.</p>
 */
public final class OcrBridge {

    private OcrBridge() {}

    public static boolean isAvailable() {
        return false;
    }

    public static String recognize(String imageUrlOrPath) {
        throw new UnsupportedOperationException("OCR not configured");
    }
}