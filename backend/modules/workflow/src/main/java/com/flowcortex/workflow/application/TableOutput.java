package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A step output that is a table: a non-empty JSON array of objects whose values are plain scalars and that share at
 * least one key. {@link #promptSample()} is the bounded part of it a model may see.
 */
final class TableOutput {

    /** Outputs of this many characters or more are not read as a table. */
    static final int MAX_OUTPUT_CHARACTERS = 200_000;
    static final int SAMPLE_ROWS = 60;
    static final int SAMPLE_COLUMNS = 20;
    static final int SAMPLE_CELL_CHARACTERS = 300;
    static final int SAMPLE_CHARACTERS = 20_000;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * The rows and columns of the sample, each cell already cut; a row holds one cell per sample column, empty where
     * the row lacks the key.
     */
    record Sample(List<String> columns, List<List<String>> rows, int rowsLeftOut, int columnsLeftOut) {
    }

    private final List<String> columns;
    private final List<JsonNode> rows;

    private TableOutput(List<String> columns, List<JsonNode> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    static Optional<TableOutput> parse(String output) {
        if (output == null || output.length() >= MAX_OUTPUT_CHARACTERS) {
            return Optional.empty();
        }
        String trimmed = output.trim();
        if (!trimmed.startsWith("[")) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            return Optional.empty();
        }
        List<JsonNode> rows = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        for (JsonNode row : root) {
            if (!row.isObject() || !onlyScalars(row)) {
                return Optional.empty();
            }
            rows.add(row);
            row.fieldNames().forEachRemaining(columns::add);
        }
        JsonNode first = rows.getFirst();
        if (first.isEmpty() || !sharesAKey(first, rows)) {
            return Optional.empty();
        }
        return Optional.of(new TableOutput(List.copyOf(columns), List.copyOf(rows)));
    }

    List<String> columns() {
        return columns;
    }

    int totalRows() {
        return rows.size();
    }

    /** Up to 60 rows of up to 20 columns, cells cut to 300 characters, rows added while the cells stay within 20,000. */
    Sample promptSample() {
        List<String> sampleColumns = columns.subList(0, Math.min(columns.size(), SAMPLE_COLUMNS));
        List<List<String>> sampleRows = new ArrayList<>();
        int characters = 0;
        for (JsonNode row : rows.subList(0, Math.min(rows.size(), SAMPLE_ROWS))) {
            List<String> cells = new ArrayList<>();
            int rowCharacters = 0;
            for (String column : sampleColumns) {
                String cell = cut(row.path(column).asText(""));
                rowCharacters += cell.length();
                cells.add(cell);
            }
            if (!sampleRows.isEmpty() && characters + rowCharacters > SAMPLE_CHARACTERS) {
                break;
            }
            characters += rowCharacters;
            sampleRows.add(cells);
        }
        return new Sample(sampleColumns, sampleRows, rows.size() - sampleRows.size(),
                columns.size() - sampleColumns.size());
    }

    private static boolean onlyScalars(JsonNode row) {
        for (Iterator<Map.Entry<String, JsonNode>> fields = row.fields(); fields.hasNext();) {
            JsonNode value = fields.next().getValue();
            if (!(value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sharesAKey(JsonNode first, List<JsonNode> rows) {
        for (Iterator<String> names = first.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (rows.stream().allMatch(row -> row.has(name))) {
                return true;
            }
        }
        return false;
    }

    /** At most {@link #SAMPLE_CELL_CHARACTERS} characters, never splitting a surrogate pair. */
    private static String cut(String text) {
        if (text.length() <= SAMPLE_CELL_CHARACTERS) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(SAMPLE_CELL_CHARACTERS - 1))
                ? SAMPLE_CELL_CHARACTERS - 1 : SAMPLE_CELL_CHARACTERS;
        return text.substring(0, end);
    }
}
