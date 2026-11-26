package com.flowcortex.connector.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The shapes the Sheets port and the member routes return. */
public final class SheetModels {

    private SheetModels() {
    }

    /** One spreadsheet of the member's Drive. */
    public record Spreadsheet(String id, String name, Instant modifiedAt, String ownerEmail, String webViewUrl) {
    }

    /** A page of spreadsheets; {@code nextCursor} is {@code null} on the last page. */
    public record SpreadsheetPage(List<Spreadsheet> items, String nextCursor) {
    }

    /** One tab and its grid size. */
    public record Tab(long sheetId, String title, int rowCount, int columnCount) {
    }

    /** A spreadsheet's title and tabs, in the spreadsheet's order. */
    public record Tabs(String spreadsheetId, String title, List<Tab> tabs) {
    }

    /** The header and rows read from one tab; each row is keyed by the header cells (or {@code col1}, {@code col2}, ...). */
    public record Preview(String spreadsheetId, String tab, String range, List<String> header,
            List<Map<String, String>> rows) {
    }

    /** What Google reports for an append: the range written and the cells updated. */
    public record AppendResult(String updatedRange, int updatedCells) {
    }
}
