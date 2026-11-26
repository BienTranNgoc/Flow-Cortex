package com.flowcortex.connector.api;

import com.flowcortex.connector.api.SheetModels.AppendResult;
import com.flowcortex.connector.api.SheetModels.Preview;
import com.flowcortex.connector.api.SheetModels.SpreadsheetPage;
import com.flowcortex.connector.api.SheetModels.Tabs;
import java.util.List;
import java.util.UUID;

/**
 * A member's Google Sheets, for the member routes and for server code. Every method fails with a
 * {@link ConnectorException}; an invalid argument is {@code INVALID_INPUT} before any request reaches Google.
 */
public interface GoogleSheets {

    /** The member's non-trashed spreadsheets, newest modified first; {@code q} filters by name, {@code limit} is 1-100. */
    SpreadsheetPage listSpreadsheets(UUID memberId, String q, int limit, String cursor);

    Tabs listTabs(UUID memberId, String spreadsheetId);

    /** The header and up to {@code limit} (1-50) rows of one tab as displayed; {@code range} may be {@code null}. */
    Preview preview(UUID memberId, String spreadsheetId, String tab, String range, int limit, boolean hasHeader);

    /**
     * Appends 1-1,000 rows (at most 10,000 cells) below the table of {@code tab} in one request, values read as if
     * typed by a user. The request is never sent twice.
     */
    AppendResult appendRows(UUID memberId, String spreadsheetId, String tab, List<List<String>> rows);
}
