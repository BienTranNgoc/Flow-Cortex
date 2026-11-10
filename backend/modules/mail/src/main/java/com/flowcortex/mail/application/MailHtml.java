package com.flowcortex.mail.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the HTML part of a mail from its plain-text body; nothing here trusts the text. */
final class MailHtml {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n{2,}");
    private static final Pattern SLOT = Pattern.compile("\\{\\{(lang|brand|body|footer)}}");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)";
    private static final String LAYOUT = loadLayout();

    private MailHtml() {
    }

    /** HTML-escapes the five characters that can open markup or leave an attribute. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Blank lines become paragraphs, single newlines line breaks, {@code http(s)} URLs anchors. */
    static String paragraphs(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        StringBuilder html = new StringBuilder();
        for (String block : PARAGRAPH_BREAK.split(normalized)) {
            if (block.isBlank()) {
                continue;
            }
            html.append("<p style=\"margin:0 0 16px 0;\">").append(linkify(block.strip()).replace("\n", "<br>"))
                    .append("</p>");
        }
        return html.toString();
    }

    /** The layout with each slot filled in one pass, so a slot-shaped string in the body stays literal. */
    static String page(String lang, String brand, String bodyHtml, String footerHtml) {
        Matcher matcher = SLOT.matcher(LAYOUT);
        StringBuilder out = new StringBuilder(LAYOUT.length() + bodyHtml.length() + footerHtml.length());
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "lang" -> escape(lang);
                case "brand" -> escape(brand);
                case "body" -> bodyHtml;
                default -> footerHtml;
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String linkify(String text) {
        Matcher matcher = URL.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String url = matcher.group();
            int end = url.length();
            while (end > 0 && TRAILING_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) {
                end--;
            }
            out.append(escape(text.substring(last, matcher.start())));
            if (end == 0) {
                out.append(escape(url));
            } else {
                String link = escape(url.substring(0, end));
                out.append("<a href=\"").append(link).append("\" style=\"color:#066058;\">").append(link)
                        .append("</a>").append(escape(url.substring(end)));
            }
            last = matcher.end();
        }
        return out.append(escape(text.substring(last))).toString();
    }

    private static String loadLayout() {
        try (InputStream in = MailHtml.class.getResourceAsStream("/mail/layout.html")) {
            if (in == null) {
                throw new IllegalStateException("mail/layout.html is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
