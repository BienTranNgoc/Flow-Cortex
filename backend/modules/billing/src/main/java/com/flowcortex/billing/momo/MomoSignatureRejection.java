package com.flowcortex.billing.momo;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * A rejected signature turned into a string comparison. MoMo answers a bad signature with the signing string
 * it expected (in {@code subErrors}, or inside the message for code 11007), so the cause becomes a diff
 * between that string and ours. The record keeps both strings whole; {@link #toLogLine} masks the access key.
 *
 * @param resultCode          MoMo's result code as text
 * @param expected            the signing string MoMo returned, or null when it returned none
 * @param built               the signing string this system built
 * @param firstDifferenceIndex index of the first differing character, null when equal or nothing compared
 * @param differingFields     fields whose values differ, MoMo-masked fields excluded
 * @param maskedFields        fields MoMo masked in its own string and which therefore could not be compared
 * @param comparisonComplete  false when nothing was compared or some field was masked
 */
public record MomoSignatureRejection(
        String resultCode,
        String expected,
        String built,
        Integer firstDifferenceIndex,
        List<String> differingFields,
        List<String> maskedFields,
        boolean comparisonComplete) {

    private static final Set<Long> SIGNATURE_CODES = Set.of(13L, 20L, 11007L);
    private static final String MASKED = "***";
    private static final String ACCESS_KEY = "accessKey";
    private static final String ELLIPSIS = "…";
    private static final int REVEALED_EDGE = 4;
    private static final int MIN_LENGTH_TO_REVEAL = REVEALED_EDGE * 3;
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.\\s]+$");

    /** The diagnostic for an answer to a request whose signing string was {@code built}; empty for any other answer. */
    public static Optional<MomoSignatureRejection> describe(String built, JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            return Optional.empty();
        }
        Long code = code(answer.has("resultCode") ? answer.get("resultCode") : answer.get("errorCode"));
        if (code == null || !SIGNATURE_CODES.contains(code)) {
            return Optional.empty();
        }
        String message = answer.get("message") != null && answer.get("message").isString()
                ? answer.get("message").asString() : null;
        String expected = fromSubErrors(answer.get("subErrors"));
        if (expected == null) {
            expected = fromMessage(message);
        }
        if (expected == null) {
            return Optional.of(new MomoSignatureRejection(String.valueOf(code), null, built, null,
                    List.of(), List.of(), false));
        }
        List<String> masked = maskedFields(expected);
        String comparable = applyMasks(masked, built);
        return Optional.of(new MomoSignatureRejection(String.valueOf(code), expected, built,
                firstDifference(expected, comparable), differingFields(expected, comparable), masked,
                masked.isEmpty()));
    }

    /** One WARN-ready line; both strings carry the access key only at its first and last four characters. */
    public String toLogLine(String endpoint) {
        return "MoMo rejected the signature: endpoint=" + endpoint
                + " resultCode=" + resultCode
                + " differingFields=" + differingFields
                + " maskedFields=" + maskedFields
                + " comparisonComplete=" + comparisonComplete
                + " firstDifferenceIndex=" + firstDifferenceIndex
                + " built=" + maskAccessKey(built)
                + " expected=" + (expected == null ? "none" : maskAccessKey(expected));
    }

    /** Shows the {@code accessKey} value of a signing string at its ends only; a MoMo-masked value is kept. */
    public static String maskAccessKey(String signingString) {
        return Arrays.stream(signingString.split("&", -1))
                .map(segment -> {
                    int separator = segment.indexOf('=');
                    if (separator <= 0 || !ACCESS_KEY.equals(segment.substring(0, separator))) {
                        return segment;
                    }
                    String value = segment.substring(separator + 1);
                    if (MASKED.equals(value) || value.length() < MIN_LENGTH_TO_REVEAL) {
                        return ACCESS_KEY + "=" + MASKED;
                    }
                    return ACCESS_KEY + "=" + value.substring(0, REVEALED_EDGE) + ELLIPSIS
                            + value.substring(value.length() - REVEALED_EDGE);
                })
                .collect(Collectors.joining("&"));
    }

    private static Long code(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The sub-error naming the signature field is preferred; otherwise any message shaped like a signing string. */
    private static String fromSubErrors(JsonNode subErrors) {
        if (subErrors == null || !subErrors.isArray()) {
            return null;
        }
        String fallback = null;
        for (JsonNode entry : subErrors) {
            JsonNode message = entry.isObject() ? entry.get("message") : null;
            if (message == null || !message.isString()) {
                continue;
            }
            JsonNode field = entry.get("field");
            if (field != null && field.isString() && "signature".equals(field.asString())) {
                return message.asString();
            }
            if (fallback == null && looksLikeSigningString(message.asString())) {
                fallback = message.asString();
            }
        }
        return fallback;
    }

    /** The sandbox wraps the string in a sentence; it runs from the first {@code accessKey=} to the end. */
    private static String fromMessage(String message) {
        if (message == null) {
            return null;
        }
        int start = message.indexOf(ACCESS_KEY + "=");
        if (start < 0) {
            return null;
        }
        String candidate = TRAILING_PUNCTUATION.matcher(message.substring(start).trim()).replaceAll("");
        return looksLikeSigningString(candidate) ? candidate : null;
    }

    private static boolean looksLikeSigningString(String value) {
        return value.contains(ACCESS_KEY + "=") && value.contains("&");
    }

    private static Map<String, String> fieldMap(String signingString) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String segment : signingString.split("&", -1)) {
            int separator = segment.indexOf('=');
            if (separator > 0) {
                fields.put(segment.substring(0, separator), segment.substring(separator + 1));
            }
        }
        return fields;
    }

    private static List<String> maskedFields(String expected) {
        return fieldMap(expected).entrySet().stream()
                .filter(entry -> MASKED.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Our string with MoMo's masks applied, so a field MoMo would not echo never reads as a difference. */
    private static String applyMasks(List<String> masked, String built) {
        if (masked.isEmpty()) {
            return built;
        }
        return Arrays.stream(built.split("&", -1))
                .map(segment -> {
                    int separator = segment.indexOf('=');
                    return separator > 0 && masked.contains(segment.substring(0, separator))
                            ? segment.substring(0, separator) + "=" + MASKED : segment;
                })
                .collect(Collectors.joining("&"));
    }

    private static Integer firstDifference(String a, String b) {
        int shared = Math.min(a.length(), b.length());
        for (int index = 0; index < shared; index++) {
            if (a.charAt(index) != b.charAt(index)) {
                return index;
            }
        }
        return a.length() == b.length() ? null : shared;
    }

    private static List<String> differingFields(String expected, String built) {
        Map<String, String> left = fieldMap(expected);
        Map<String, String> right = fieldMap(built);
        Set<String> names = new TreeSet<>(left.keySet());
        names.addAll(right.keySet());
        return names.stream().filter(name -> !java.util.Objects.equals(left.get(name), right.get(name))).toList();
    }
}
