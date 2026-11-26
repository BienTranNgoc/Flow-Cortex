package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a batch's input, either a list of rows or the text of a file, into the ordered rows the batch will run, and
 * checks the size limits on the way. Pure: nothing is stored here, and a breach names the field and, for a row too
 * long, the row's index in the resulting list.
 */
final class BatchInput {

    static final int MAX_ROWS = 500;
    static final int MAX_ROW_LENGTH = 10_000;
    static final int MAX_TOTAL_LENGTH = 2_000_000;
    static final int DEFAULT_CONCURRENCY = 1;
    static final int MAX_CONCURRENCY = 10;

    /** The declared layout of {@code content}; none means detect. */
    enum Format {
        CSV, JSON, JSONL
    }

    /** Why an input is refused. */
    enum Reason {
        EXACTLY_ONE_INPUT, NOT_A_JSON_ARRAY, NO_ROWS, TOO_MANY_ROWS, ROW_TOO_LONG, TOO_LONG, CONCURRENCY_OUT_OF_RANGE
    }

    /** A refused input: {@code field} is the request field to name, {@code rowIndex} is set only for a row too long. */
    static final class Invalid extends RuntimeException {

        private final String field;
        private final Reason reason;
        private final Integer rowIndex;

        Invalid(String field, Reason reason, Integer rowIndex) {
            super(rowIndex == null ? field + ": " + reason : field + "[" + rowIndex + "]: " + reason);
            this.field = field;
            this.reason = reason;
            this.rowIndex = rowIndex;
        }

        String field() {
            return field;
        }

        Reason reason() {
            return reason;
        }

        Integer rowIndex() {
            return rowIndex;
        }
    }

    private BatchInput() {
    }

    /**
     * @param rows entries to trim, empty ones dropped; exactly one of {@code rows} and {@code content} is given
     * @param content the file text; CRLF becomes LF and the text is trimmed before it is split
     * @param format the declared layout, or {@code null} to detect a JSON array
     * @param skipFirstLine drops the first line of line-split content (a CSV header)
     */
    static List<String> parse(List<String> rows, String content, Format format, boolean skipFirstLine) {
        if ((rows == null) == (content == null)) {
            throw new Invalid(rows == null ? "content" : "rows", Reason.EXACTLY_ONE_INPUT, null);
        }
        if (rows != null) {
            return checked("rows", fromRows(rows), totalLength(rows));
        }
        return checked("content", fromContent(content, format, skipFirstLine), content.length());
    }

    /** The batch's concurrency: one when not given, otherwise one to ten. */
    static int concurrency(Integer requested) {
        if (requested == null) {
            return DEFAULT_CONCURRENCY;
        }
        if (requested < 1 || requested > MAX_CONCURRENCY) {
            throw new Invalid("concurrency", Reason.CONCURRENCY_OUT_OF_RANGE, null);
        }
        return requested;
    }

    private static List<String> checked(String field, List<String> rows, long totalLength) {
        if (totalLength > MAX_TOTAL_LENGTH) {
            throw new Invalid(field, Reason.TOO_LONG, null);
        }
        if (rows.isEmpty()) {
            throw new Invalid(field, Reason.NO_ROWS, null);
        }
        if (rows.size() > MAX_ROWS) {
            throw new Invalid(field, Reason.TOO_MANY_ROWS, null);
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).length() > MAX_ROW_LENGTH) {
                throw new Invalid(field, Reason.ROW_TOO_LONG, index);
            }
        }
        return List.copyOf(rows);
    }

    private static long totalLength(List<String> rows) {
        return rows.stream().filter(row -> row != null).mapToLong(String::length).sum();
    }

    private static List<String> fromRows(List<String> rows) {
        return rows.stream().filter(row -> row != null).map(String::strip).filter(row -> !row.isEmpty()).toList();
    }

    private static List<String> fromContent(String content, Format format, boolean skipFirstLine) {
        String text = content.replace("\r\n", "\n").strip();
        boolean declaredJson = format == Format.JSON;
        boolean mayBeJson = declaredJson || format == null && text.startsWith("[") && text.endsWith("]");
        if (mayBeJson) {
            List<String> values = jsonArrayValues(text);
            if (values != null) {
                return values;
            }
            if (declaredJson) {
                throw new Invalid("content", Reason.NOT_A_JSON_ARRAY, null);
            }
        }
        return lines(text, skipFirstLine);
    }

    /** One entry per value of a JSON array, or {@code null} when the text is not an array of non-null values. */
    private static List<String> jsonArrayValues(String text) {
        JsonNode tree;
        try {
            tree = DataStoreJson.mapper().readTree(text);
        } catch (JsonProcessingException notJson) {
            return null;
        }
        if (tree == null || !tree.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>(tree.size());
        for (JsonNode value : tree) {
            if (value.isNull()) {
                return null;
            }
            values.add(value.isTextual() ? value.textValue() : value.toString());
        }
        return values;
    }

    private static List<String> lines(String text, boolean skipFirstLine) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            String row = line.strip();
            if (!row.isEmpty()) {
                lines.add(row);
            }
        }
        return skipFirstLine && !lines.isEmpty() ? lines.subList(1, lines.size()) : lines;
    }
}
