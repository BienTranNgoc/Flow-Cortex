package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.TableOutput.Sample;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Which step outputs are tables, and the bounded sample a model may see. */
class TableOutputTest {

    private static String rows(int count, int columns, String cell) {
        return IntStream.range(0, count)
                .mapToObj(r -> IntStream.range(0, columns)
                        .mapToObj(c -> "\"c" + c + "\":\"" + cell + "\"")
                        .collect(Collectors.joining(",", "{", "}")))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String oneCell(int length) {
        return "[{\"a\":\"" + "x".repeat(length - "[{\"a\":\"\"}]".length()) + "\"}]";
    }

    @Test
    void anArrayOfObjectsIsATable() {
        TableOutput table = TableOutput.parse("[{\"city\":\"Hanoi\",\"sales\":120},{\"city\":\"Hue\",\"sales\":80}]")
                .orElseThrow();

        assertThat(table.columns()).containsExactly("city", "sales");
        assertThat(table.totalRows()).isEqualTo(2);
    }

    @Test
    void aNestedValueIsNotATable() {
        assertThat(TableOutput.parse("[{\"city\":\"Hanoi\",\"tags\":[\"a\"]}]")).isEmpty();
    }

    @Test
    void rowsWithNoSharedKeyAreNotATable() {
        assertThat(TableOutput.parse("[{\"a\":1},{\"b\":2}]")).isEmpty();
    }

    @Test
    void anObjectIsNotATable() {
        assertThat(TableOutput.parse("{\"a\":1}")).isEmpty();
    }

    @Test
    void emptyShapesAreNotTables() {
        assertThat(TableOutput.parse("[]")).isEmpty();
        assertThat(TableOutput.parse("[{}]")).isEmpty();
        assertThat(TableOutput.parse("[null]")).isEmpty();
    }

    @Test
    void textAndBrokenJsonAreNotTables() {
        assertThat(TableOutput.parse("Hello world")).isEmpty();
        assertThat(TableOutput.parse("[{\"a\":1}")).isEmpty();
        assertThat(TableOutput.parse(null)).isEmpty();
    }

    @Test
    void anOutputOfTwoHundredThousandCharactersIsNotATable() {
        assertThat(TableOutput.parse(oneCell(200_000))).isEmpty();
        assertThat(TableOutput.parse(oneCell(199_999))).isPresent();
    }

    @Test
    void columnsAreTheUnionInFirstSeenOrder() {
        TableOutput table = TableOutput.parse("[{\"a\":1,\"b\":2},{\"a\":3,\"c\":4}]").orElseThrow();

        assertThat(table.columns()).containsExactly("a", "b", "c");
    }

    @Test
    void aLongTableIsSampledToSixtyRows() {
        TableOutput table = TableOutput.parse(rows(75, 2, "v")).orElseThrow();

        Sample sample = table.promptSample();

        assertThat(sample.rows()).hasSize(60);
        assertThat(table.totalRows()).isEqualTo(75);
        assertThat(sample.rowsLeftOut()).isEqualTo(15);
        assertThat(sample.columnsLeftOut()).isZero();
    }

    @Test
    void wideCellsAreCutAndTheSampleStaysBounded() {
        // The table limit (200,000 characters) admits 60 rows of 10 columns only up to cells of about 320 characters.
        TableOutput table = TableOutput.parse(rows(60, 10, "w".repeat(320))).orElseThrow();

        Sample sample = table.promptSample();

        assertThat(sample.rows()).allSatisfy(row -> assertThat(row).allSatisfy(cell -> assertThat(cell).hasSize(300)));
        int characters = sample.rows().stream().flatMap(List::stream).mapToInt(String::length).sum();
        assertThat(characters).isLessThanOrEqualTo(20_000);
        assertThat(sample.rows().size()).isLessThan(60);
        assertThat(sample.rowsLeftOut()).isEqualTo(60 - sample.rows().size());
    }

    @Test
    void moreThanTwentyColumnsAreLeftOut() {
        TableOutput table = TableOutput.parse(rows(2, 25, "v")).orElseThrow();

        Sample sample = table.promptSample();

        assertThat(sample.columns()).hasSize(20);
        assertThat(sample.rows()).allSatisfy(row -> assertThat(row).hasSize(20));
        assertThat(sample.columnsLeftOut()).isEqualTo(5);
        assertThat(table.columns()).hasSize(25);
    }
}
