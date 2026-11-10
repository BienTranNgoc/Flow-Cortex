package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTableTest {

    private static final String BOM = "﻿";

    private static String oneCell(Object cell) {
        String csv = CsvTable.render(List.of("h"), List.of(Arrays.asList(cell)));
        return csv.substring(BOM.length() + "\"h\"\r\n".length());
    }

    @Test
    void quotesEveryCellAndDoublesInnerQuotes() {
        String csv = CsvTable.render(List.of("Name", "Note"), List.of(List.of("a", "say \"hi\"")));

        assertThat(csv).isEqualTo(BOM + "\"Name\",\"Note\"\r\n\"a\",\"say \"\"hi\"\"\"\r\n");
    }

    @Test
    void keepsCommasAndLineBreaksInsideOneCell() {
        assertThat(oneCell("a,b\nc\r\nd")).isEqualTo("\"a,b\nc\r\nd\"\r\n");
    }

    @Test
    void prefixesATextCellStartingWithAFormulaCharacter() {
        for (String start : List.of("=", "+", "-", "@", "\t", "\r")) {
            assertThat(oneCell(start + "SUM(A1)")).isEqualTo("\"'" + start + "SUM(A1)\"\r\n");
        }
    }

    @Test
    void leavesANegativeNumberCellUnprefixed() {
        assertThat(oneCell(-5L)).isEqualTo("\"-5\"\r\n");
        assertThat(oneCell(-1.5)).isEqualTo("\"-1.5\"\r\n");
    }

    @Test
    void rendersNullAsEmptyAndInstantsAsIsoUtc() {
        assertThat(oneCell(null)).isEqualTo("\"\"\r\n");
        assertThat(oneCell(Instant.parse("2026-11-08T05:00:00Z"))).isEqualTo("\"2026-11-08T05:00:00Z\"\r\n");
    }

    @Test
    void startsWithAByteOrderMarkAndEndsRowsWithCrlf() {
        String csv = CsvTable.render(List.of("a"), List.of(List.of("x"), List.of("y")));

        assertThat(csv).startsWith(BOM);
        assertThat(csv.substring(BOM.length()).split("(?<=\r\n)"))
                .containsExactly("\"a\"\r\n", "\"x\"\r\n", "\"y\"\r\n");
    }
}
