package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTableTest {

    private static final String BOM = "﻿";

    @Test
    void quotesEveryCellAndDoublesInnerQuotes() {
        String csv = CsvTable.render(List.of("Name", "Note"), List.of(List.of("Ann", "say \"hi\"")));

        assertThat(csv).isEqualTo(BOM + "\"Name\",\"Note\"\r\n\"Ann\",\"say \"\"hi\"\"\"\r\n");
    }

    @Test
    void keepsCommasAndLineBreaksInsideOneCell() {
        String csv = CsvTable.render(List.of("A"), List.of(List.of("x,y\nz")));

        assertThat(csv).isEqualTo(BOM + "\"A\"\r\n\"x,y\nz\"\r\n");
    }

    @Test
    void prefixesATextCellStartingWithAFormulaCharacter() {
        for (String lead : new String[] {"=", "+", "-", "@", "\t", "\r"}) {
            String csv = CsvTable.render(List.of("A"), List.of(List.of(lead + "SUM(1)")));

            assertThat(csv).as("lead %s", lead.replace("\t", "TAB").replace("\r", "CR"))
                    .isEqualTo(BOM + "\"A\"\r\n\"'" + lead + "SUM(1)\"\r\n");
        }
    }

    @Test
    void leavesANegativeNumberCellUnprefixed() {
        String csv = CsvTable.render(List.of("A", "B"), List.of(List.of(-5L, -2.5)));

        assertThat(csv).isEqualTo(BOM + "\"A\",\"B\"\r\n\"-5\",\"-2.5\"\r\n");
    }

    @Test
    void rendersNullAsEmptyAndInstantsAsIsoUtc() {
        String csv = CsvTable.render(List.of("A", "B"),
                List.of(Arrays.asList(null, Instant.parse("2026-11-01T03:04:05Z"))));

        assertThat(csv).isEqualTo(BOM + "\"A\",\"B\"\r\n\"\",\"2026-11-01T03:04:05Z\"\r\n");
    }

    @Test
    void startsWithAByteOrderMarkAndEndsRowsWithCrlf() {
        String csv = CsvTable.render(List.of("A"), List.of(List.of("1"), List.of("2")));

        assertThat(csv).startsWith(BOM).endsWith("\r\n");
        assertThat(csv.substring(1).split("\r\n")).containsExactly("\"A\"", "\"1\"", "\"2\"");
    }
}
