package com.flowcortex.asset.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The file name a downloaded asset is saved under: its title plus the extension of its MIME type. A
 * title is member-controlled text that ends up in a response header, so everything that could break
 * the header or the saved path is dropped here.
 */
public final class AssetFileNames {

    static final int MAX_BASE_CODE_POINTS = 200;

    /** MIME type to the extension written, then the other spellings that already count as it. */
    private static final Map<String, List<String>> EXTENSIONS = Map.ofEntries(
            Map.entry("video/mp4", List.of("mp4")),
            Map.entry("video/quicktime", List.of("mov")),
            Map.entry("video/webm", List.of("webm")),
            Map.entry("video/x-matroska", List.of("mkv")),
            Map.entry("audio/mpeg", List.of("mp3")),
            Map.entry("audio/wav", List.of("wav")),
            Map.entry("audio/mp4", List.of("m4a")),
            Map.entry("image/jpeg", List.of("jpg", "jpeg")),
            Map.entry("image/png", List.of("png")),
            Map.entry("image/webp", List.of("webp")));

    private AssetFileNames() {}

    public static String fileName(UUID id, String title, String mimeType) {
        List<String> extensions = EXTENSIONS.getOrDefault(baseMimeType(mimeType), List.of());
        String cleaned = clean(title);
        String suffix = "";
        String base = cleaned;
        if (!extensions.isEmpty()) {
            suffix = "." + extensions.get(0);
            for (String extension : extensions) {
                String dotted = "." + extension;
                if (cleaned.toLowerCase(Locale.ROOT).endsWith(dotted)) {
                    suffix = cleaned.substring(cleaned.length() - dotted.length());
                    base = cleaned.substring(0, cleaned.length() - dotted.length()).strip();
                    break;
                }
            }
        }
        if (base.isEmpty()) {
            base = id.toString();
        }
        return cut(base) + suffix;
    }

    private static String baseMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int parameters = mimeType.indexOf(';');
        String type = parameters < 0 ? mimeType : mimeType.substring(0, parameters);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    private static String clean(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(title.length());
        Normalizer.normalize(title, Normalizer.Form.NFC).codePoints().filter(AssetFileNames::isSafe)
                .forEach(kept::appendCodePoint);
        return kept.toString().strip();
    }

    private static boolean isSafe(int codePoint) {
        if (codePoint == '"' || codePoint == '\\' || codePoint == '/') {
            return false;
        }
        int type = Character.getType(codePoint);
        if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
            return false;
        }
        return !isBidiControl(codePoint);
    }

    /** Marks that reorder the text around them and so can disguise an extension. */
    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061C
                || codePoint == 0x200E || codePoint == 0x200F
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static String cut(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_BASE_CODE_POINTS) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_BASE_CODE_POINTS)).stripTrailing();
    }
}
