package com.flowcortex.workflow.web;

import java.time.Instant;
import java.util.List;

/** RFC 4180 CSV with a byte order mark so Excel reads UTF-8, and spreadsheet-formula neutralised text cells. */
final class CsvTable {

    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";
    private static final String FORMULA_STARTS = "=+-@\t\r";

    private CsvTable() {
    }

    static String render(List<String> header, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        appendRow(out, header);
        for (List<Object> row : rows) {
            appendRow(out, row);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(cell(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        out.append(EOL);
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        String text = value.toString();
        if (!(value instanceof Number) && !text.isEmpty() && FORMULA_STARTS.indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }
}
