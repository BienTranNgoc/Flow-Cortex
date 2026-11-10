package com.flowcortex.asset.domain;

import java.text.Normalizer;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure title rules for library assets; every title is NFC, whitespace-collapsed and at most 200 code points. */
public final class AssetNaming {

    static final int MAX_TITLE_CODE_POINTS = 200;
    static final int SHORT_INPUT_CODE_POINTS = 60;

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SEPARATOR = " · ";

    private AssetNaming() {}

    /** Base of a run asset title: {@code <short input> · <workflow name>}, else the workflow name, else {@code Run <8 hex>}. */
    public static String runTitleBase(String input, String workflowName, UUID runId) {
        String shortInput = cut(firstNonBlankLine(input), SHORT_INPUT_CODE_POINTS);
        String name = normalize(workflowName);
        if (!shortInput.isEmpty() && !name.isEmpty()) {
            return shortInput + SEPARATOR + name;
        }
        if (!name.isEmpty()) {
            return name;
        }
        if (!shortInput.isEmpty()) {
            return shortInput;
        }
        return "Run " + runId.toString().substring(0, 8);
    }

    /**
     * Full title: {@code <base> (version) · index}; the version shows from 2 and the index only when the run has
     * several outputs. The base is cut so the suffix stays whole.
     */
    public static String runTitle(String base, int version, int index, int total) {
        StringBuilder suffix = new StringBuilder();
        if (version >= 2) {
            suffix.append(" (").append(version).append(')');
        }
        if (total >= 2) {
            suffix.append(SEPARATOR).append(index);
        }
        int room = Math.max(1, MAX_TITLE_CODE_POINTS - suffix.codePointCount(0, suffix.length()));
        return cut(normalize(base), room) + suffix;
    }

    /**
     * Title of an uploaded input: its file name when the run bound exactly one input and has that name, otherwise
     * {@code <workflow name> · <binding key>}.
     */
    public static String uploadTitle(String inputFileName, int boundInputs, String workflowName, String bindingKey) {
        String fileName = normalize(inputFileName);
        if (boundInputs == 1 && !fileName.isEmpty()) {
            return cut(fileName, MAX_TITLE_CODE_POINTS);
        }
        String name = normalize(workflowName);
        String key = normalize(bindingKey);
        String title = name.isEmpty() ? key : key.isEmpty() ? name : name + SEPARATOR + key;
        return cut(title.isEmpty() ? "Upload" : title, MAX_TITLE_CODE_POINTS);
    }

    private static String firstNonBlankLine(String input) {
        if (input == null) {
            return "";
        }
        for (String line : LINE_BREAK.split(Normalizer.normalize(input, Normalizer.Form.NFC))) {
            String collapsed = normalize(line);
            if (!collapsed.isEmpty()) {
                return collapsed;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    private static String cut(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints)).stripTrailing();
    }
}
