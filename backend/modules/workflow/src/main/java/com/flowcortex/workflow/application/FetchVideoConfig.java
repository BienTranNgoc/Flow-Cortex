package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJob;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The link rule and the config of a {@code fetch_video} step. Fault texts name the rule that was
 * broken and never repeat the value, which may carry credentials.
 */
final class FetchVideoConfig {

    static final String TYPE = "fetch_video";
    static final Set<String> QUALITIES = Set.of("best", "1080p", "720p", "audio_only");
    private static final Set<String> KEYS = Set.of("url", "quality", "extractAudio");
    private static final Pattern LINK = Pattern.compile("^https?://([^/?#]*)", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper CONFIG_READER = new ObjectMapper();

    private FetchVideoConfig() {
    }

    /**
     * What is wrong with {@code link} (surrounding whitespace is ignored), or {@code null} when it is
     * an {@code http} or {@code https} link with a host, no user info, no whitespace or control
     * character and at most {@link MediaJob#MAX_FETCH_URL_LENGTH} characters.
     */
    static String linkFault(String link) {
        if (link == null || link.isBlank()) {
            return "the link is empty";
        }
        String trimmed = link.trim();
        if (trimmed.length() > MediaJob.MAX_FETCH_URL_LENGTH) {
            return "the link is longer than " + MediaJob.MAX_FETCH_URL_LENGTH + " characters";
        }
        if (trimmed.chars().anyMatch(FetchVideoConfig::isWhitespaceOrControl)) {
            return "the link contains a space or a control character";
        }
        Matcher matcher = LINK.matcher(trimmed);
        if (!matcher.find()) {
            return "the link must start with http:// or https://";
        }
        String authority = matcher.group(1);
        if (authority.indexOf('@') >= 0) {
            return "the link must not contain a user name or password";
        }
        int portSeparator = authority.lastIndexOf(':');
        String host = portSeparator >= 0 && authority.indexOf(']') < portSeparator
                ? authority.substring(0, portSeparator) : authority;
        if (host.isEmpty()) {
            return "the link has no host";
        }
        return null;
    }

    /**
     * What is wrong with the config of a {@code fetch_video} step, or {@code null}: only {@code url},
     * {@code quality} and {@code extractAudio} are accepted, each within its rule.
     */
    static String fault(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = CONFIG_READER.readTree(config);
        } catch (JsonProcessingException e) {
            return "config is not JSON";
        }
        if (root == null || !root.isObject()) {
            return "config is not a JSON object";
        }
        for (String key : (Iterable<String>) root::fieldNames) {
            if (!KEYS.contains(key)) {
                return "config accepts only url, quality and extractAudio";
            }
        }
        JsonNode url = root.get("url");
        if (url != null) {
            if (!url.isTextual()) {
                return "url must be a link";
            }
            String linkFault = linkFault(url.textValue());
            if (linkFault != null) {
                return "url: " + linkFault;
            }
        }
        JsonNode quality = root.get("quality");
        if (quality != null && !(quality.isTextual() && QUALITIES.contains(quality.textValue()))) {
            return "quality must be one of best, 1080p, 720p, audio_only";
        }
        JsonNode extractAudio = root.get("extractAudio");
        if (extractAudio != null && !extractAudio.isBoolean()) {
            return "extractAudio must be true or false";
        }
        return null;
    }

    /** The trimmed config {@code url}, or empty when the config is not an object with a non-blank text {@code url}. */
    static Optional<String> configUrl(String config) {
        if (config == null || config.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode url = CONFIG_READER.readTree(config).get("url");
            return url != null && url.isTextual() && !url.textValue().isBlank()
                    ? Optional.of(url.textValue().trim())
                    : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static boolean isWhitespaceOrControl(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint);
    }
}
