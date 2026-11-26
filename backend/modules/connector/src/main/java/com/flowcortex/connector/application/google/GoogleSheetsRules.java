package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules for what a member or server code may ask of Sheets: bounds, quoting and the shape of a preview. Every
 * violation is {@code INVALID_INPUT} with {@code connector.google.invalidRequest}, raised before any request reaches
 * Google.
 */
public final class GoogleSheetsRules {

    /** A tab read as a header and rows keyed by it; the header lists the keys in column order. */
    public record Table(List<String> header, List<Map<String, String>> rows) {
    }

    static final int MAX_LIST_LIMIT = 100;
    static final int MAX_PREVIEW_LIMIT = 50;
    static final int MAX_QUERY = 100;
    static final int MAX_TAB = 100;
    static final int MAX_ID = 200;
    static final int MAX_CURSOR = 2048;
    static final int MAX_APPEND_ROWS = 1000;
    static final int MAX_APPEND_CELLS = 10_000;
    static final int MAX_CELL_CHARS = 50_000;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,200}");
    private static final Pattern RANGE = Pattern.compile("[A-Z]{1,3}[1-9][0-9]{0,6}(:[A-Z]{1,3}[1-9][0-9]{0,6})?");

    private GoogleSheetsRules() {
    }

    /** The tab name as it must appear in a range: in single quotes, a quote inside doubled. */
    public static String quoteTab(String tab) {
        if (tab == null || tab.isBlank() || tab.length() > MAX_TAB) {
            throw invalid();
        }
        return "'" + tab.replace("'", "''") + "'";
    }

    /**
     * The range to read: the tab plus {@code range} when given, else columns A-Z from row 1 to the header (if any)
     * plus {@code limit} rows.
     */
    public static String previewRange(String tab, String range, int limit, boolean hasHeader) {
        String quoted = quoteTab(tab);
        requirePreviewLimit(limit);
        if (range == null || range.isBlank()) {
            return quoted + "!A1:Z" + (hasHeader ? limit + 1 : limit);
        }
        String cells = range.strip().toUpperCase(Locale.ROOT);
        if (!RANGE.matcher(cells).matches()) {
            throw invalid();
        }
        return quoted + "!" + cells;
    }

    public static void requireListLimit(int limit) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw invalid();
        }
    }

    public static void requirePreviewLimit(int limit) {
        if (limit < 1 || limit > MAX_PREVIEW_LIMIT) {
            throw invalid();
        }
    }

    public static void requireQuery(String q) {
        if (q != null && q.length() > MAX_QUERY) {
            throw invalid();
        }
    }

    public static void requireSpreadsheetId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw invalid();
        }
    }

    /** The Drive query clause for names containing {@code q} literally. */
    public static String nameQuery(String q) {
        return "name contains '" + escapeLiteral(q) + "'";
    }

    /** {@code value} made safe inside a single-quoted Drive query literal. */
    static String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** A query parameter bound as text: blank is {@code fallback}, anything that is not an int is invalid. */
    public static int intParam(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    public static boolean boolParam(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid();
        };
    }

    /** A page cursor handed back to Google as is; blank means the first page. */
    public static String cursor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_CURSOR) {
            throw invalid();
        }
        return raw;
    }

    /** The first row as header (when {@code hasHeader}) and the rest as rows keyed by it, short rows filled with {@code ""}. */
    public static Table rows(List<List<String>> values, boolean hasHeader) {
        if (values.isEmpty()) {
            return new Table(List.of(), List.of());
        }
        List<String> header = hasHeader ? headerKeys(values.getFirst()) : columnKeys(width(values));
        List<List<String>> body = hasHeader ? values.subList(1, values.size()) : values;
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> cells : body) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < Math.max(header.size(), cells.size()); i++) {
                String key = i < header.size() ? header.get(i) : "col" + (i + 1);
                row.put(key, i < cells.size() ? cells.get(i) : "");
            }
            rows.add(row);
        }
        return new Table(header, rows);
    }

    /** The failure for rows that may not be appended, or {@code null} when they may. */
    public static ConnectorException appendFault(List<List<String>> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_APPEND_ROWS) {
            return invalid();
        }
        int cells = 0;
        for (List<String> row : rows) {
            if (row == null) {
                return invalid();
            }
            cells += row.size();
            if (cells > MAX_APPEND_CELLS) {
                return invalid();
            }
            for (String cell : row) {
                if (cell != null && cell.length() > MAX_CELL_CHARS) {
                    return invalid();
                }
            }
        }
        return null;
    }

    static ConnectorException invalid() {
        return new ConnectorException(Reason.INVALID_INPUT, new LocalizedMessage("connector.google.invalidRequest"));
    }

    private static List<String> headerKeys(List<String> cells) {
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            String key = cell == null || cell.isBlank() || !seen.add(cell) ? "col" + (i + 1) : cell;
            keys.add(key);
        }
        return keys;
    }

    private static List<String> columnKeys(int width) {
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= width; i++) {
            keys.add("col" + i);
        }
        return keys;
    }

    private static int width(List<List<String>> values) {
        int width = 0;
        for (List<String> row : values) {
            width = Math.max(width, row.size());
        }
        return width;
    }
}
