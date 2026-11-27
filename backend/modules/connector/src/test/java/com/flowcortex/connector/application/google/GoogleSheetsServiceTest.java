package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.SheetModels.AppendResult;
import com.flowcortex.connector.api.SheetModels.Preview;
import com.flowcortex.connector.api.SheetModels.Spreadsheet;
import com.flowcortex.connector.api.SheetModels.SpreadsheetPage;
import com.flowcortex.connector.api.SheetModels.Tab;
import com.flowcortex.connector.api.SheetModels.Tabs;
import com.flowcortex.connector.application.google.GoogleTokens.GoogleAccess;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleSheetsServiceTest {

    private static final String TOKEN = "access-token";
    private static final String SHEET_MIME = "application/vnd.google-apps.spreadsheet";

    private final GoogleTokens tokens = mock(GoogleTokens.class);
    private final GoogleWorkspaceApi api = mock(GoogleWorkspaceApi.class);
    private final GoogleSheetsService sheets = new GoogleSheetsService(tokens, api);
    private final UUID member = UUID.randomUUID();

    private void tokenIssued() {
        when(tokens.forUse(member)).thenReturn(new GoogleAccess(TOKEN, null));
    }

    private static Reply ok(Map<String, Object> body) {
        return new Reply(200, body, null, null);
    }

    private static void assertInvalidInput(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ConnectorException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.INVALID_INPUT));
    }

    @Test
    void listingFiltersToNonTrashedSpreadsheetsNewestFirstAndMapsThePage() {
        tokenIssued();
        when(api.driveList(TOKEN, "mimeType='" + SHEET_MIME + "' and trashed=false", "modifiedTime desc", 50, null))
                .thenReturn(ok(Map.of("nextPageToken", "next-1", "files", List.of(
                        Map.of("id", "s1", "name", "Leads", "modifiedTime", "2026-11-01T10:00:00.000Z",
                                "owners", List.of(Map.of("emailAddress", "owner@example.com")),
                                "webViewLink", "https://docs.google.com/spreadsheets/d/s1/edit"),
                        Map.of("id", "s2", "name", "Bare")))));

        SpreadsheetPage page = sheets.listSpreadsheets(member, null, 50, null);

        assertThat(page.nextCursor()).isEqualTo("next-1");
        assertThat(page.items()).containsExactly(
                new Spreadsheet("s1", "Leads", Instant.parse("2026-11-01T10:00:00Z"), "owner@example.com",
                        "https://docs.google.com/spreadsheets/d/s1/edit"),
                new Spreadsheet("s2", "Bare", null, null, null));
    }

    @Test
    void listingPassesTheEscapedNameFilterPageSizeAndCursor() {
        tokenIssued();
        when(api.driveList(anyString(), anyString(), anyString(), anyInt(), any())).thenReturn(ok(Map.of()));

        SpreadsheetPage page = sheets.listSpreadsheets(member, "Q4' or name contains '", 20, "cursor-1");

        verify(api).driveList(TOKEN, "mimeType='" + SHEET_MIME + "' and trashed=false and "
                + "name contains 'Q4\\' or name contains \\''", "modifiedTime desc", 20, "cursor-1");
        assertThat(page).isEqualTo(new SpreadsheetPage(List.of(), null));
    }

    @Test
    void anInvalidListOrIdOrPreviewTakesNoTokenAndMakesNoRequest() {
        assertInvalidInput(() -> sheets.listSpreadsheets(member, null, 0, null));
        assertInvalidInput(() -> sheets.listSpreadsheets(member, null, 101, null));
        assertInvalidInput(() -> sheets.listSpreadsheets(member, "q".repeat(101), 10, null));
        assertInvalidInput(() -> sheets.listTabs(member, "../x"));
        assertInvalidInput(() -> sheets.preview(member, "1SheetAbc", "Leads", "A1:B2!Sheet2", 5, true));
        assertInvalidInput(() -> sheets.preview(member, "1SheetAbc", " ", null, 5, true));
        assertInvalidInput(() -> sheets.preview(member, "1SheetAbc", "Leads", null, 51, true));
        assertInvalidInput(() -> sheets.appendRows(member, "1SheetAbc", "Leads", List.of()));
        verifyNoInteractions(api, tokens);
    }

    @Test
    void tabsKeepTheSpreadsheetOrderAndGridSizes() {
        tokenIssued();
        when(api.sheetsGet(TOKEN, "1SheetAbc")).thenReturn(ok(Map.of(
                "properties", Map.of("title", "CRM"),
                "sheets", List.of(
                        Map.of("properties", Map.of("sheetId", 0, "title", "Leads",
                                "gridProperties", Map.of("rowCount", 1000, "columnCount", 26))),
                        Map.of("properties", Map.of("sheetId", 77, "title", "Archive",
                                "gridProperties", Map.of("rowCount", 500, "columnCount", 10)))))));

        Tabs tabs = sheets.listTabs(member, "1SheetAbc");

        assertThat(tabs).isEqualTo(new Tabs("1SheetAbc", "CRM",
                List.of(new Tab(0, "Leads", 1000, 26), new Tab(77, "Archive", 500, 10))));
    }

    @Test
    void anExcelFileIsReportedAsNotANativeSheet() {
        tokenIssued();
        when(api.sheetsGet(TOKEN, "1SheetAbc")).thenReturn(new Reply(400, Map.of(),
                new Fault("failedPrecondition", "global", "This operation is not supported for this document"), null));

        assertThatThrownBy(() -> sheets.listTabs(member, "1SheetAbc"))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.providerReason()).isEqualTo("not_native_sheet");
                    assertThat(ex.detail().code()).isEqualTo("connector.google.notNativeSheet");
                });
    }

    @Test
    void aQuotaAnswerKeepsTheWaitGoogleAsked() {
        tokenIssued();
        when(api.sheetsGet(TOKEN, "1SheetAbc")).thenReturn(new Reply(429, Map.of(),
                new Fault("RESOURCE_EXHAUSTED", null, "Google text"), Duration.ofSeconds(30)));

        assertThatThrownBy(() -> sheets.listTabs(member, "1SheetAbc"))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.RATE_LIMITED);
                    assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void previewReadsTheQuotedRangeAndKeysRowsByTheHeader() {
        tokenIssued();
        when(api.valuesGet(TOKEN, "1SheetAbc", "'Leads'!A1:Z6")).thenReturn(ok(Map.of("range", "Leads!A1:Z6",
                "values", List.of(List.of("Name", "Email"), List.of("An", "an@example.com"), List.of("Bình")))));

        Preview preview = sheets.preview(member, "1SheetAbc", "Leads", null, 5, true);

        assertThat(preview.spreadsheetId()).isEqualTo("1SheetAbc");
        assertThat(preview.tab()).isEqualTo("Leads");
        assertThat(preview.range()).isEqualTo("Leads!A1:Z6");
        assertThat(preview.header()).containsExactly("Name", "Email");
        assertThat(preview.rows()).containsExactly(Map.of("Name", "An", "Email", "an@example.com"),
                Map.of("Name", "Bình", "Email", ""));
    }

    @Test
    void previewWithoutHeaderUsesColumnNumbersAndStopsAtTheLimit() {
        tokenIssued();
        when(api.valuesGet(TOKEN, "1SheetAbc", "'Bob''s tab'!A1:F20")).thenReturn(ok(Map.of(
                "values", List.of(List.of("a"), List.of("b"), List.of("c")))));

        Preview preview = sheets.preview(member, "1SheetAbc", "Bob's tab", "A1:F20", 2, false);

        assertThat(preview.range()).isEqualTo("'Bob''s tab'!A1:F20");
        assertThat(preview.header()).containsExactly("col1");
        assertThat(preview.rows()).containsExactly(Map.of("col1", "a"), Map.of("col1", "b"));
    }

    @Test
    void anEmptyTabPreviewsAsNothing() {
        tokenIssued();
        when(api.valuesGet(TOKEN, "1SheetAbc", "'Leads'!A1:Z6")).thenReturn(ok(Map.of("range", "Leads!A1:Z6")));

        Preview preview = sheets.preview(member, "1SheetAbc", "Leads", null, 5, true);

        assertThat(preview.header()).isEmpty();
        assertThat(preview.rows()).isEmpty();
    }

    @Test
    void appendSendsOneRequestToTheQuotedTabAndReturnsWhatWasUpdated() {
        tokenIssued();
        List<List<String>> rows = List.of(List.of("An", "an@example.com"), List.of("Bình", "binh@example.com"));
        when(api.valuesAppend(TOKEN, "1SheetAbc", "'Leads'", rows)).thenReturn(ok(Map.of("updates",
                Map.of("updatedRange", "Leads!A12:B13", "updatedCells", 4))));

        AppendResult result = sheets.appendRows(member, "1SheetAbc", "Leads", rows);

        assertThat(result).isEqualTo(new AppendResult("Leads!A12:B13", 4));
        verify(api).valuesAppend(TOKEN, "1SheetAbc", "'Leads'", rows);
    }

    @Test
    void anAppendFailureOnTheServerSideIsNotRetryable() {
        tokenIssued();
        when(api.valuesAppend(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(new Reply(503, Map.of(), null, null));

        assertThatThrownBy(() -> sheets.appendRows(member, "1SheetAbc", "Leads", List.of(List.of("x"))))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE);
                    assertThat(ex.retryable()).isFalse();
                });
    }

    @Test
    void whenUsageIsOffNoRequestIsMade() {
        when(tokens.forUse(member)).thenThrow(ConnectorException.of(Reason.USAGE_DISABLED));

        assertThatThrownBy(() -> sheets.appendRows(member, "1SheetAbc", "Leads", List.of(List.of("x"))))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.USAGE_DISABLED));
        assertThatThrownBy(() -> sheets.listSpreadsheets(member, null, 10, null))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.USAGE_DISABLED));
        verifyNoInteractions(api);
    }
}
