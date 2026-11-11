package com.flowcortex.workflow.web;

import java.time.Instant;
import java.util.List;

/**
 * Renders a table as an RFC 4180 CSV that Excel opens correctly: a leading byte-order mark keeps
 * Vietnamese text from being read as ANSI, and a text cell that starts like a formula is prefixed
 * with an apostrophe so a spreadsheet never evaluates it.
 */
final class CsvTable {

    private static final String BOM = "﻿";
    private static final String ROW_END = "\r\n";

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
        out.append(ROW_END);
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        String text = value.toString();
        return startsLikeAFormula(text) ? "'" + text : text;
    }

    private static boolean startsLikeAFormula(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
