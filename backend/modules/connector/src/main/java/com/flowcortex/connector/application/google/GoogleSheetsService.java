package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.GoogleSheets;
import com.flowcortex.connector.api.SheetModels.AppendResult;
import com.flowcortex.connector.api.SheetModels.Preview;
import com.flowcortex.connector.api.SheetModels.Spreadsheet;
import com.flowcortex.connector.api.SheetModels.SpreadsheetPage;
import com.flowcortex.connector.api.SheetModels.Tab;
import com.flowcortex.connector.api.SheetModels.Tabs;
import com.flowcortex.connector.application.google.GoogleErrorRules.Api;
import com.flowcortex.connector.application.google.GoogleErrorRules.Item;
import com.flowcortex.connector.application.google.GoogleSheetsRules.Table;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A member's Google Sheets through the member's own token. Arguments are checked by {@link GoogleSheetsRules}
 * before the token is taken or any request is made; every Google refusal goes through {@link GoogleErrorRules}.
 */
@Service
public class GoogleSheetsService implements GoogleSheets {

    private static final String SHEET_FILTER = "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false";

    private final GoogleTokens tokens;
    private final GoogleWorkspaceApi api;

    GoogleSheetsService(GoogleTokens tokens, GoogleWorkspaceApi api) {
        this.tokens = tokens;
        this.api = api;
    }

    @Override
    public SpreadsheetPage listSpreadsheets(UUID memberId, String q, int limit, String cursor) {
        GoogleSheetsRules.requireListLimit(limit);
        GoogleSheetsRules.requireQuery(q);
        String pageToken = GoogleSheetsRules.cursor(cursor);
        String query = q == null || q.isBlank() ? SHEET_FILTER : SHEET_FILTER + " and " + GoogleSheetsRules.nameQuery(q);
        String token = tokens.forUse(memberId).accessToken();
        Reply reply = api.driveList(token, query, "modifiedTime desc", limit, pageToken);
        if (!reply.ok()) {
            throw failure(Api.DRIVE, reply);
        }
        List<Spreadsheet> items = new ArrayList<>();
        if (reply.body().get("files") instanceof List<?> files) {
            for (Object file : files) {
                if (file instanceof Map<?, ?> map && text(map.get("id")) != null) {
                    String name = text(map.get("name"));
                    items.add(new Spreadsheet(text(map.get("id")), name == null ? "" : name,
                            instant(text(map.get("modifiedTime"))), ownerEmail(map.get("owners")),
                            text(map.get("webViewLink"))));
                }
            }
        }
        return new SpreadsheetPage(items, text(reply.body().get("nextPageToken")));
    }

    @Override
    public Tabs listTabs(UUID memberId, String spreadsheetId) {
        GoogleSheetsRules.requireSpreadsheetId(spreadsheetId);
        String token = tokens.forUse(memberId).accessToken();
        Reply reply = api.sheetsGet(token, spreadsheetId);
        if (!reply.ok()) {
            throw failure(Api.SHEETS, reply);
        }
        String title = reply.body().get("properties") instanceof Map<?, ?> properties
                ? text(properties.get("title")) : null;
        List<Tab> tabs = new ArrayList<>();
        if (reply.body().get("sheets") instanceof List<?> sheets) {
            for (Object sheet : sheets) {
                if (sheet instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> properties) {
                    Map<?, ?> grid = properties.get("gridProperties") instanceof Map<?, ?> found ? found : Map.of();
                    tabs.add(new Tab(number(properties.get("sheetId")), nonNull(text(properties.get("title"))),
                            (int) number(grid.get("rowCount")), (int) number(grid.get("columnCount"))));
                }
            }
        }
        return new Tabs(spreadsheetId, nonNull(title), tabs);
    }

    @Override
    public Preview preview(UUID memberId, String spreadsheetId, String tab, String range, int limit,
            boolean hasHeader) {
        GoogleSheetsRules.requireSpreadsheetId(spreadsheetId);
        String requested = GoogleSheetsRules.previewRange(tab, range, limit, hasHeader);
        String token = tokens.forUse(memberId).accessToken();
        Reply reply = api.valuesGet(token, spreadsheetId, requested);
        if (!reply.ok()) {
            throw failure(Api.SHEETS, reply);
        }
        Table table = GoogleSheetsRules.rows(cells(reply.body().get("values")), hasHeader);
        List<Map<String, String>> rows = table.rows().size() > limit ? table.rows().subList(0, limit) : table.rows();
        String read = text(reply.body().get("range"));
        return new Preview(spreadsheetId, tab, read == null ? requested : read, table.header(), List.copyOf(rows));
    }

    @Override
    public AppendResult appendRows(UUID memberId, String spreadsheetId, String tab, List<List<String>> rows) {
        GoogleSheetsRules.requireSpreadsheetId(spreadsheetId);
        String quotedTab = GoogleSheetsRules.quoteTab(tab);
        ConnectorException fault = GoogleSheetsRules.appendFault(rows);
        if (fault != null) {
            throw fault;
        }
        List<List<String>> values = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            values.add(row.stream().map(GoogleSheetsService::nonNull).toList());
        }
        String token = tokens.forUse(memberId).accessToken();
        Reply reply = api.valuesAppend(token, spreadsheetId, quotedTab, values);
        if (!reply.ok()) {
            throw failure(Api.SHEETS, reply);
        }
        Map<?, ?> updates = reply.body().get("updates") instanceof Map<?, ?> found ? found : Map.of();
        return new AppendResult(nonNull(text(updates.get("updatedRange"))), (int) number(updates.get("updatedCells")));
    }

    private static ConnectorException failure(Api api, Reply reply) {
        Fault fault = reply.fault();
        return GoogleErrorRules.classify(api, Item.OTHER, reply.status(), fault == null ? null : fault.reason(),
                fault == null ? null : fault.domain(), fault == null ? null : fault.message(), reply.retryAfter());
    }

    private static List<List<String>> cells(Object values) {
        List<List<String>> rows = new ArrayList<>();
        if (values instanceof List<?> list) {
            for (Object row : list) {
                List<String> cells = new ArrayList<>();
                if (row instanceof List<?> items) {
                    for (Object cell : items) {
                        cells.add(cell == null ? "" : String.valueOf(cell));
                    }
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String ownerEmail(Object owners) {
        return owners instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> owner
                ? text(owner.get("emailAddress")) : null;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
