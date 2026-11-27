package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.application.google.GoogleSheetsRules.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleSheetsRulesTest {

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ConnectorException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(Reason.INVALID_INPUT);
            assertThat(ex.detail().code()).isEqualTo("connector.google.invalidRequest");
        });
    }

    @Test
    void aTabNameIsQuotedWithSingleQuotesDoubled() {
        assertThat(GoogleSheetsRules.quoteTab("Q4 Sales")).isEqualTo("'Q4 Sales'");
        assertThat(GoogleSheetsRules.quoteTab("Bob's tab")).isEqualTo("'Bob''s tab'");
    }

    @Test
    void theDefaultPreviewRangeCoversTheHeaderPlusLimitRows() {
        assertThat(GoogleSheetsRules.previewRange("Leads", null, 5, true)).isEqualTo("'Leads'!A1:Z6");
        assertThat(GoogleSheetsRules.previewRange("Leads", null, 5, false)).isEqualTo("'Leads'!A1:Z5");
        assertThat(GoogleSheetsRules.previewRange("Leads", "   ", 5, true)).isEqualTo("'Leads'!A1:Z6");
    }

    @Test
    void anExplicitRangeIsUsedAsGiven() {
        assertThat(GoogleSheetsRules.previewRange("Leads", "A1:F20", 5, true)).isEqualTo("'Leads'!A1:F20");
        assertThat(GoogleSheetsRules.previewRange("Leads", "b2", 5, true)).isEqualTo("'Leads'!B2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1:B2!Sheet2", "ZZZZ1", "A1:", "A:C", "'Other'!A1", "A1:B2:C3", "A0"})
    void aRangeThatIsNotPlainA1NotationIsRefused(String range) {
        assertInvalid(() -> GoogleSheetsRules.previewRange("Leads", range, 5, true));
    }

    @Test
    void aTabThatIsBlankOrOver100CharactersIsRefused() {
        assertInvalid(() -> GoogleSheetsRules.quoteTab(" "));
        assertInvalid(() -> GoogleSheetsRules.quoteTab(null));
        assertInvalid(() -> GoogleSheetsRules.quoteTab("t".repeat(101)));
        assertThatCode(() -> GoogleSheetsRules.quoteTab("t".repeat(100))).doesNotThrowAnyException();
    }

    @Test
    void previewLimitsOutsideOneToFiftyAreRefused() {
        assertInvalid(() -> GoogleSheetsRules.previewRange("Leads", null, 0, true));
        assertInvalid(() -> GoogleSheetsRules.previewRange("Leads", null, 51, true));
        assertThat(GoogleSheetsRules.previewRange("Leads", null, 50, false)).isEqualTo("'Leads'!A1:Z50");
    }

    @Test
    void listLimitsOutsideOneToAHundredAndLongQueriesAreRefused() {
        assertInvalid(() -> GoogleSheetsRules.requireListLimit(0));
        assertInvalid(() -> GoogleSheetsRules.requireListLimit(101));
        assertInvalid(() -> GoogleSheetsRules.requireQuery("q".repeat(101)));
        assertThatCode(() -> GoogleSheetsRules.requireListLimit(100)).doesNotThrowAnyException();
        assertThatCode(() -> GoogleSheetsRules.requireQuery("q".repeat(100))).doesNotThrowAnyException();
        assertThatCode(() -> GoogleSheetsRules.requireQuery(null)).doesNotThrowAnyException();
    }

    @Test
    void aNameFilterCannotLeaveItsQuotes() {
        assertThat(GoogleSheetsRules.nameQuery("Q4' or name contains '"))
                .isEqualTo("name contains 'Q4\\' or name contains \\''");
        assertThat(GoogleSheetsRules.nameQuery("a\\b")).isEqualTo("name contains 'a\\\\b'");
    }

    @Test
    void queryParametersAreReadAsStringsAndRefusedWhenMalformed() {
        assertThat(GoogleSheetsRules.intParam(null, 50)).isEqualTo(50);
        assertThat(GoogleSheetsRules.intParam(" ", 50)).isEqualTo(50);
        assertThat(GoogleSheetsRules.intParam("20", 50)).isEqualTo(20);
        assertInvalid(() -> GoogleSheetsRules.intParam("abc", 50));
        assertInvalid(() -> GoogleSheetsRules.intParam("99999999999", 50));
        assertThat(GoogleSheetsRules.boolParam(null, true)).isTrue();
        assertThat(GoogleSheetsRules.boolParam("false", true)).isFalse();
        assertThat(GoogleSheetsRules.boolParam("TRUE", false)).isTrue();
        assertInvalid(() -> GoogleSheetsRules.boolParam("yes", true));
    }

    @Test
    void aCursorIsBlankToNullAndBoundedInLength() {
        assertThat(GoogleSheetsRules.cursor("  ")).isNull();
        assertThat(GoogleSheetsRules.cursor("abc")).isEqualTo("abc");
        assertInvalid(() -> GoogleSheetsRules.cursor("c".repeat(2049)));
    }

    @Test
    void aSpreadsheetIdIsOneToTwoHundredLettersDigitsDashAndUnderscore() {
        assertThatCode(() -> GoogleSheetsRules.requireSpreadsheetId("1SheetAbc")).doesNotThrowAnyException();
        assertThatCode(() -> GoogleSheetsRules.requireSpreadsheetId("a-b_C9")).doesNotThrowAnyException();
        assertInvalid(() -> GoogleSheetsRules.requireSpreadsheetId("../x"));
        assertInvalid(() -> GoogleSheetsRules.requireSpreadsheetId("i".repeat(201)));
        assertInvalid(() -> GoogleSheetsRules.requireSpreadsheetId(""));
        assertInvalid(() -> GoogleSheetsRules.requireSpreadsheetId(null));
    }

    @Test
    void rowsAreKeyedByTheHeaderAndShortRowsAreFilledWithEmptyText() {
        Table table = GoogleSheetsRules.rows(
                List.of(List.of("Name", "Email"), List.of("An", "an@example.com"), List.of("Bình")), true);

        assertThat(table.header()).containsExactly("Name", "Email");
        assertThat(table.rows()).containsExactly(Map.of("Name", "An", "Email", "an@example.com"),
                Map.of("Name", "Bình", "Email", ""));
    }

    @Test
    void withoutAHeaderRowsAreKeyedByColumnNumber() {
        Table table = GoogleSheetsRules.rows(List.of(List.of("An", "an@example.com"), List.of("Bình")), false);

        assertThat(table.header()).containsExactly("col1", "col2");
        assertThat(table.rows()).containsExactly(Map.of("col1", "An", "col2", "an@example.com"),
                Map.of("col1", "Bình", "col2", ""));
    }

    @Test
    void blankAndRepeatedHeaderCellsAndExtraCellsFallBackToColumnNumbers() {
        Table table = GoogleSheetsRules.rows(List.of(List.of("Name", "", "Name"), List.of("a", "b", "c", "d")), true);

        assertThat(table.header()).containsExactly("Name", "col2", "col3");
        assertThat(table.rows()).containsExactly(Map.of("Name", "a", "col2", "b", "col3", "c", "col4", "d"));
    }

    @Test
    void anEmptyTabGivesAnEmptyTable() {
        assertThat(GoogleSheetsRules.rows(List.of(), true)).isEqualTo(new Table(List.of(), List.of()));
        assertThat(GoogleSheetsRules.rows(List.of(), false)).isEqualTo(new Table(List.of(), List.of()));
    }

    @Test
    void anAppendOfTwoRowsOfTwoIsAccepted() {
        assertThat(GoogleSheetsRules.appendFault(List.of(List.of("An", "a@x.io"), List.of("Bình", "b@x.io")))).isNull();
    }

    @Test
    void anAppendOutsideTheBoundsIsRefusedAsInvalidInput() {
        List<List<String>> tooManyRows = new ArrayList<>();
        List<List<String>> tooManyCells = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooManyRows.add(List.of("x"));
        }
        for (int i = 0; i < 1000; i++) {
            tooManyCells.add(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"));
        }
        List<List<String>> bigCell = List.of(List.of("c".repeat(50_001)));

        for (List<List<String>> rows : List.of(List.<List<String>>of(), tooManyRows, tooManyCells, bigCell)) {
            ConnectorException fault = GoogleSheetsRules.appendFault(rows);
            assertThat(fault).isNotNull();
            assertThat(fault.reason()).isEqualTo(Reason.INVALID_INPUT);
            assertThat(fault.detail().code()).isEqualTo("connector.google.invalidRequest");
        }
        assertThat(GoogleSheetsRules.appendFault(null)).isNotNull();
        assertThat(GoogleSheetsRules.appendFault(List.of(List.of("c".repeat(50_000))))).isNull();
    }
}
