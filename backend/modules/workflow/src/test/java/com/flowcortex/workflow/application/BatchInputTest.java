package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flowcortex.workflow.application.BatchInput.Format;
import com.flowcortex.workflow.application.BatchInput.Invalid;
import com.flowcortex.workflow.application.BatchInput.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class BatchInputTest {

    private static List<String> content(String text, Format format, boolean skipFirstLine) {
        return BatchInput.parse(null, text, format, skipFirstLine);
    }

    private static Invalid rejected(ThrowingCallable parse) {
        Throwable thrown = catchThrowable(parse);
        assertThat(thrown).isInstanceOf(Invalid.class);
        return (Invalid) thrown;
    }

    @Test
    void csvSplitsNonEmptyTrimmedLinesAndNormalisesCrlf() {
        assertThat(content("  red shoes \r\n\r\nblue hat\r\n", Format.CSV, false)).containsExactly("red shoes",
                "blue hat");
    }

    @Test
    void skipFirstLineDropsTheHeader() {
        assertThat(content("prompt\r\nred shoes\r\n\r\nblue hat\r\n", Format.CSV, true)).containsExactly("red shoes",
                "blue hat");
    }

    @Test
    void jsonArrayDetectedKeepsStringsAndSerialisesOtherValues() {
        assertThat(content("[\"a\", {\"k\": 1}, 3]", null, false)).containsExactly("a", "{\"k\":1}", "3");
    }

    @Test
    void jsonlDetectedByFirstLine() {
        assertThat(content("{\"k\":1}\n{\"k\":2}", null, false)).containsExactly("{\"k\":1}", "{\"k\":2}");
        assertThat(content("{\"k\":1}\n{\"k\":2}", Format.JSONL, false)).containsExactly("{\"k\":1}", "{\"k\":2}");
    }

    @Test
    void formatOverridesDetection() {
        assertThat(content("[\"a\", \"b\"]", Format.CSV, false)).containsExactly("[\"a\", \"b\"]");
        assertThat(content("[\"a\", \"b\"]", Format.JSONL, false)).containsExactly("[\"a\", \"b\"]");
        assertThat(content("[\"a\", \"b\"]", Format.JSON, true)).containsExactly("a", "b");
    }

    @Test
    void declaredJsonThatDoesNotParseOrHoldsANullIsRejectedNamingContent() {
        for (String broken : List.of("[\"a\", ", "[\"a\", null]", "{\"k\": 1}", "plain text", "[\"a\"] trailing")) {
            Invalid invalid = rejected(() -> content(broken, Format.JSON, false));

            assertThat(invalid.field()).as(broken).isEqualTo("content");
            assertThat(invalid.reason()).isEqualTo(Reason.NOT_A_JSON_ARRAY);
            assertThat(invalid.rowIndex()).isNull();
        }
    }

    @Test
    void detectedBracketsThatDoNotParseFallBackToLines() {
        assertThat(content("[1]\n[2]", null, false)).containsExactly("[1]", "[2]");
        assertThat(content("[unclosed text]", null, false)).containsExactly("[unclosed text]");
        assertThat(content("[\"a\", null]", null, false)).containsExactly("[\"a\", null]");
    }

    @Test
    void rowsEntriesAreTrimmedAndEmptyOnesDropped() {
        assertThat(BatchInput.parse(List.of("  red shoes ", "", "   ", "blue hat"), null, null, false))
                .containsExactly("red shoes", "blue hat");
    }

    @Test
    void fiveHundredRowsPassFiveHundredOneFail() {
        assertThat(BatchInput.parse(numbered(500), null, null, false)).hasSize(500);

        Invalid fromRows = rejected(() -> BatchInput.parse(numbered(501), null, null, false));
        assertThat(fromRows.field()).isEqualTo("rows");
        assertThat(fromRows.reason()).isEqualTo(Reason.TOO_MANY_ROWS);

        Invalid fromContent = rejected(() -> content(String.join("\n", numbered(501)), null, false));
        assertThat(fromContent.field()).isEqualTo("content");
        assertThat(fromContent.reason()).isEqualTo(Reason.TOO_MANY_ROWS);
    }

    @Test
    void aRowOfTenThousandOneCharactersIsRejectedWithItsIndex() {
        String limit = "x".repeat(10_000);
        assertThat(content("a\n" + limit, null, false)).hasSize(2);

        Invalid invalid = rejected(() -> content("a\nb\nc\n" + limit + "y", null, false));

        assertThat(invalid.field()).isEqualTo("content");
        assertThat(invalid.reason()).isEqualTo(Reason.ROW_TOO_LONG);
        assertThat(invalid.rowIndex()).isEqualTo(3);

        Invalid fromRows = rejected(() -> BatchInput.parse(List.of("a", limit + "y"), null, null, false));
        assertThat(fromRows.field()).isEqualTo("rows");
        assertThat(fromRows.rowIndex()).isEqualTo(1);
    }

    @Test
    void rowsSummingOverTwoMillionCharactersAreRejectedNamingRows() {
        String full = "x".repeat(10_000);
        List<String> fits = new ArrayList<>(IntStream.range(0, 200).mapToObj(i -> full).toList());
        assertThat(BatchInput.parse(fits, null, null, false)).hasSize(200);

        List<String> over = new ArrayList<>(fits);
        over.add("y");
        Invalid invalid = rejected(() -> BatchInput.parse(over, null, null, false));
        assertThat(invalid.field()).isEqualTo("rows");
        assertThat(invalid.reason()).isEqualTo(Reason.TOO_LONG);

        Invalid fromContent = rejected(() -> content("x".repeat(2_000_001), null, false));
        assertThat(fromContent.field()).isEqualTo("content");
        assertThat(fromContent.reason()).isEqualTo(Reason.TOO_LONG);
    }

    @Test
    void nothingToRunIsRejectedNamingTheFieldSent() {
        Invalid blankRows = rejected(() -> BatchInput.parse(List.of(" ", ""), null, null, false));
        Invalid headerOnly = rejected(() -> content("prompt\n", Format.CSV, true));
        Invalid emptyArray = rejected(() -> content("[]", Format.JSON, false));

        assertThat(blankRows.field()).isEqualTo("rows");
        assertThat(blankRows.reason()).isEqualTo(Reason.NO_ROWS);
        assertThat(headerOnly.field()).isEqualTo("content");
        assertThat(headerOnly.reason()).isEqualTo(Reason.NO_ROWS);
        assertThat(emptyArray.reason()).isEqualTo(Reason.NO_ROWS);
    }

    @Test
    void exactlyOneOfRowsAndContentIsRequired() {
        assertThat(rejected(() -> BatchInput.parse(List.of("a"), "b", null, false)).reason())
                .isEqualTo(Reason.EXACTLY_ONE_INPUT);
        assertThat(rejected(() -> BatchInput.parse(null, null, null, false)).reason())
                .isEqualTo(Reason.EXACTLY_ONE_INPUT);
    }

    @Test
    void concurrencyDefaultsToOneAndMustBeOneToTen() {
        assertThat(BatchInput.concurrency(null)).isEqualTo(1);
        assertThat(BatchInput.concurrency(1)).isEqualTo(1);
        assertThat(BatchInput.concurrency(10)).isEqualTo(10);

        for (int outOfRange : new int[] {0, 11, -3}) {
            Invalid invalid = rejected(() -> BatchInput.concurrency(outOfRange));
            assertThat(invalid.field()).isEqualTo("concurrency");
            assertThat(invalid.reason()).isEqualTo(Reason.CONCURRENCY_OUT_OF_RANGE);
        }
    }

    private static List<String> numbered(int count) {
        return IntStream.range(0, count).mapToObj(i -> "row " + i).toList();
    }
}
